/// The Dart runtime.
///
/// Two operations, deliberately separate:
///
/// * [Runtime.execute] runs a program to completion and hands back everything
///   it produced. Use it when you want the answer.
/// * [Runtime.spawn] starts a program and hands back a [KryonProcess] you can
///   write to, read from and signal while it runs. Use it when you want a
///   conversation.
library;

import 'dart:async';
import 'dart:convert';
import 'dart:io' as io;

import 'errors.dart';
import 'model.dart';

final bool _windows = io.Platform.isWindows;

/// Preserved even when `clearEnv` is set. Windows binaries -- including the
/// ones in `System32` -- routinely fail to start without these.
const List<String> _windowsEssential = ['SystemRoot', 'SystemDrive'];

/// An execution context holding default options.
///
/// A `Runtime` holds configuration, not state. It is safe to share, and
/// creating one is cheap enough that you can also just make a new one:
///
/// ```dart
/// final runtime = Runtime(const ExecutionOptions(
///   encoding: utf8,
///   timeout: Duration(seconds: 30),
/// ));
/// final result = await runtime.execute('git', ['status', '--porcelain']);
/// ```
///
/// Any default can be overridden per call. `env` merges with the runtime's
/// `env`; every other option is replaced.
class Runtime {
  Runtime([ExecutionOptions? defaults])
      : defaults = defaults ?? const ExecutionOptions() {
    this.defaults.validate();
  }

  /// The options this runtime applies when a call does not override them.
  final ExecutionOptions defaults;

  /// Run [executable] with [arguments] and return what happened.
  ///
  /// Arguments are passed to the operating system as a vector. Nothing in them
  /// is interpreted -- `execute('echo', [r'$HOME && rm -rf /'])` prints that
  /// text literally. For shell semantics you must ask for them by name; see
  /// [executeShell].
  ///
  /// A process that could not be started throws. A process that started and
  /// then failed is returned, because a non-zero exit is information: `grep`
  /// exits `1` to mean "no match". Pass `check: true` to throw on those too.
  ///
  /// Throws [CommandNotFoundException], [PermissionDeniedException],
  /// [ProcessStartFailedException] or [InvalidArgumentsException].
  Future<ExecutionResult> execute(
    String executable, [
    List<String> arguments = const [],
    ExecutionOptions? options,
  ]) =>
      _run(executable, arguments, options, shell: false);

  /// Run [commandLine] through the system shell.
  ///
  /// **The shell interprets quoting, globbing, variable expansion, pipes and
  /// command chaining. Building this string from untrusted input is a
  /// command-injection vulnerability.** If you are interpolating a value, you
  /// almost certainly want [execute] with an argument list instead.
  ///
  /// The shell is `/bin/sh -c` on POSIX and `cmd /c` on Windows.
  ///
  /// This method exists as a separate name on purpose. A `shell: true` flag
  /// sitting among a dozen options is easy to set by accident and easy to miss
  /// in review; a differently named method is not.
  Future<ExecutionResult> executeShell(
    String commandLine, [
    ExecutionOptions? options,
  ]) =>
      _run(commandLine, const [], options, shell: true);

  Future<ExecutionResult> _run(
    String executable,
    List<String> arguments,
    ExecutionOptions? overrides, {
    required bool shell,
  }) async {
    final options = defaults.mergedWith(overrides)..validate();
    _normalise(executable);
    await _checkCwd(options.cwd);

    final watch = Stopwatch()..start();
    final process = await _start(executable, arguments, options, shell: shell);

    final reason = _Reason();
    final out = _Sink(options.maxOutputBytes);
    final err = _Sink(options.maxOutputBytes);

    void intervene(TerminationReason why) {
      if (reason.set(why)) unawaited(_stop(process, options.killGrace));
    }

    final pumps = [
      process.stdout
          .listen((data) {
            if (out.add(data)) intervene(TerminationReason.outputLimit);
          })
          .asFuture<void>()
          .catchError((_) {}),
      process.stderr
          .listen((data) {
            if (err.add(data)) intervene(TerminationReason.outputLimit);
          })
          .asFuture<void>()
          .catchError((_) {}),
    ];

    _writeStdin(process, options);

    Timer? timer;
    if (options.timeout != null) {
      timer =
          Timer(options.timeout!, () => intervene(TerminationReason.timeout));
    }

    final code = await process.exitCode;
    timer?.cancel();
    await Future.wait(pumps);

    final outcome = _classify(code, reason.value);
    final result = ExecutionResult(
      executable: executable,
      arguments: List.unmodifiable(arguments),
      exitCode: outcome.exitCode,
      signal: outcome.signal,
      stdout: _decode(out.value(), options.encoding),
      stderr: _decode(err.value(), options.encoding),
      duration: watch.elapsed,
      termination: outcome.termination,
      pid: process.pid,
      stdoutTruncated: out.truncated,
      stderrTruncated: err.truncated,
    );
    return options.check ? result.checked() : result;
  }

  /// Start [executable] and return a [KryonProcess] to interact with.
  ///
  /// Returns as soon as the process has started. Always `close()` it, ideally
  /// in a `finally`, so it cannot outlive the work that needed it:
  ///
  /// ```dart
  /// final proc = await runtime.spawn('dart', ['run', 'worker.dart']);
  /// try {
  ///   proc.write('job-1\n');
  ///   await for (final chunk in proc.output) { /* ... */ }
  /// } finally {
  ///   await proc.close();
  /// }
  /// ```
  Future<KryonProcess> spawn(
    String executable, [
    List<String> arguments = const [],
    ExecutionOptions? options,
  ]) async {
    final merged = defaults.mergedWith(options)..validate();
    _normalise(executable);
    await _checkCwd(merged.cwd);
    final process = await _start(executable, arguments, merged, shell: false);
    return KryonProcess._(process, executable, arguments, merged);
  }
}

/// A running process you can talk to.
///
/// Output arrives through [output] as [OutputChunk]s in the order Kryon
/// observed them. Chunk boundaries mean nothing -- they reflect how the
/// operating system delivered the data, not lines or records.
///
/// The stream is bounded. If you stop consuming, Kryon stops reading, the pipe
/// fills and the child blocks. That is backpressure working, not a hang.
///
/// Construct via [Runtime.spawn], not directly.
class KryonProcess {
  KryonProcess._(
      this._process, this._executable, this._arguments, this._options) {
    _controller = StreamController<OutputChunk>(
      onListen: _resumeSources,
      onPause: _pauseSources,
      onResume: _resumeSources,
      onCancel: _detach,
    );

    // Subscribe immediately, then pause. Subscribing lazily on first listen
    // means a process nobody reads from never has its pipes drained, and the
    // paused subscription is what provides backpressure until someone does.
    _listen(OutputStream.stdout, _process.stdout);
    _listen(OutputStream.stderr, _process.stderr);
    _pauseSources();

    if (_options.timeout != null) {
      _timer = Timer(_options.timeout!, () {
        if (running && _reason.set(TerminationReason.timeout)) {
          unawaited(_stop(_process, _options.killGrace));
        }
      });
    }
    unawaited(_process.exitCode.then((code) => _exitCode = code));
  }

  final io.Process _process;
  final String _executable;
  final List<String> _arguments;
  final ExecutionOptions _options;
  final _Reason _reason = _Reason();
  final Stopwatch _watch = Stopwatch()..start();

  late final StreamController<OutputChunk> _controller;
  final List<StreamSubscription<List<int>>> _subscriptions = [];
  int _openSources = 2;
  int _bytesSeen = 0;
  int? _exitCode;
  bool _closed = false;
  bool _stdinClosed = false;
  Timer? _timer;

  /// The operating-system process id.
  int get pid => _process.pid;

  /// Whether the process is still alive.
  bool get running => _exitCode == null;

  /// The exit status once the process has been reaped, otherwise null.
  int? get exitCode => _exitCode;

  /// Chunks of output until both pipes reach end-of-input.
  ///
  /// A single-subscription stream: a process has one output stream, and two
  /// listeners would each get an arbitrary half of it.
  Stream<OutputChunk> get output => _controller.stream;

  void _listen(OutputStream tag, Stream<List<int>> source) {
    _subscriptions.add(
      source.listen(
        (data) {
          _bytesSeen += data.length;
          final limit = _options.maxOutputBytes;
          if (limit != null &&
              _bytesSeen > limit &&
              _reason.set(TerminationReason.outputLimit)) {
            unawaited(_stop(_process, _options.killGrace));
          }
          if (!_controller.isClosed) _controller.add(OutputChunk(tag, data));
        },
        onDone: _sourceDone,
        onError: (Object _) => _sourceDone(),
        cancelOnError: false,
      ),
    );
  }

  void _sourceDone() {
    _openSources -= 1;
    if (_openSources <= 0 && !_controller.isClosed) _controller.close();
  }

  void _pauseSources() {
    for (final subscription in _subscriptions) {
      subscription.pause();
    }
  }

  void _resumeSources() {
    for (final subscription in _subscriptions) {
      subscription.resume();
    }
  }

  void _detach() {
    for (final subscription in _subscriptions) {
      unawaited(subscription.cancel());
    }
    _subscriptions.clear();
  }

  /// Write to the child's stdin.
  ///
  /// Throws if stdin is already closed -- dropping input silently is the
  /// failure mode that produces hangs nobody can reproduce.
  void write(Object data) {
    if (_stdinClosed) {
      throw StateError('stdin of pid $pid is closed');
    }
    final bytes = data is String
        ? (_options.encoding ?? utf8).encode(data)
        : data as List<int>;
    try {
      _process.stdin.add(bytes);
    } on io.SocketException {
      // The child stopped reading. Its exit code, not this write, is the story.
    }
  }

  /// Close stdin, signalling end-of-input to the child.
  Future<void> closeStdin() async {
    if (_stdinClosed) return;
    _stdinClosed = true;
    try {
      // Bounded: closing a pipe whose reader has already died can otherwise
      // wait on a flush that will never complete.
      await _process.stdin.close().timeout(const Duration(seconds: 5));
    } catch (_) {
      // A broken pipe, or a child that exited first. Its exit code is the
      // story, not this close.
    }
  }

  /// Send a specific signal to the process.
  ///
  /// Throws [UnsupportedPlatformException] on Windows, which has no signals to
  /// send. Use [terminate] there, and know that it does not give the child a
  /// chance to clean up.
  void signal(io.ProcessSignal signal) {
    if (_windows) {
      throw const UnsupportedPlatformException(
        'Windows has no signals; use terminate(), which kills the process '
        'outright without letting it clean up',
      );
    }
    _process.kill(signal);
  }

  /// Request a polite stop: `SIGTERM` on POSIX, `TerminateProcess` on Windows.
  ///
  /// On Windows this is identical to [kill]. There is no graceful stop.
  void terminate() => _process.kill(io.ProcessSignal.sigterm);

  /// Force a stop: `SIGKILL` on POSIX, `TerminateProcess` on Windows.
  void kill() => _process.kill(io.ProcessSignal.sigkill);

  /// Wait for exit and return the outcome.
  ///
  /// `stdout` and `stderr` on the result are empty: the output was streamed to
  /// you through [output] and is deliberately not buffered a second time.
  ///
  /// Throws [ProcessTimeoutException] if [timeout] elapses. The process is left
  /// running -- this is a wait, not a stop.
  Future<ExecutionResult> wait([Duration? timeout]) async {
    var code = _exitCode;
    if (code == null) {
      final pending = _process.exitCode;
      if (timeout == null) {
        code = await pending;
      } else {
        code = await pending.timeout(
          timeout,
          onTimeout: () => throw ProcessTimeoutException(
            "'$_executable' (pid $pid) still running after $timeout",
          ),
        );
      }
      _exitCode = code;
    }
    _timer?.cancel();

    final outcome = _classify(code, _reason.value);
    final empty = _options.encoding != null ? '' : const <int>[];
    return ExecutionResult(
      executable: _executable,
      arguments: List.unmodifiable(_arguments),
      exitCode: outcome.exitCode,
      signal: outcome.signal,
      stdout: empty,
      stderr: empty,
      duration: _watch.elapsed,
      termination: outcome.termination,
      pid: pid,
    );
  }

  /// Terminate the process if it is still running and release every resource.
  ///
  /// Idempotent. Call it in a `finally`.
  Future<void> close() async {
    if (_closed) return;
    _closed = true;
    _timer?.cancel();
    await closeStdin();

    if (running) {
      _reason.set(TerminationReason.cancelled);
      await _stop(_process, _options.killGrace);
    }
    // A paused subscription never sees `done`, so an unconsumed process would
    // hold its pipes open forever. Resuming and cancelling releases them.
    _resumeSources();
    _detach();
    if (!_controller.isClosed) {
      // On a single-subscription controller, close() only completes once the
      // done event has been *delivered* -- which never happens if nobody
      // listened. Awaiting it unconditionally hangs close() forever.
      final closing = _controller.close();
      if (_controller.hasListener) await closing;
    }
    _exitCode ??= await _process.exitCode;
  }

  @override
  String toString() => 'KryonProcess(pid: $pid, $_executable, '
      '${running ? 'running' : 'exited $_exitCode'})';
}

// ---------------------------------------------------------------- internals

/// Validate the command, or throw [InvalidArgumentsException].
///
/// Shorter than its siblings on purpose: Dart's type system already rules out a
/// non-string argument at compile time, so the only mistake left to catch at
/// runtime is an empty executable.
void _normalise(String executable) {
  if (executable.isEmpty) {
    throw const InvalidArgumentsException('executable must not be empty');
  }
}

Future<void> _checkCwd(String? cwd) async {
  if (cwd == null) return;
  final directory = io.Directory(cwd);
  if (!await directory.exists()) {
    if (await io.File(cwd).exists()) {
      throw ProcessStartFailedException(
        'working directory is not a directory: $cwd',
      );
    }
    throw ProcessStartFailedException('working directory does not exist: $cwd');
  }
}

Map<String, String>? _buildEnv(ExecutionOptions options) {
  if (!options.clearEnv && options.env.isEmpty) return null;

  final env = <String, String>{};
  if (options.clearEnv) {
    if (_windows) {
      for (final key in _windowsEssential) {
        final value = io.Platform.environment[key];
        if (value != null) env[key] = value;
      }
    }
  } else {
    env.addAll(io.Platform.environment);
  }

  options.env.forEach((key, value) {
    if (value == null) {
      env.remove(key);
    } else {
      env[key] = value;
    }
  });
  return env;
}

Future<io.Process> _start(
  String executable,
  List<String> arguments,
  ExecutionOptions options, {
  required bool shell,
}) async {
  final environment = _buildEnv(options);

  // Spelled out rather than using `runInShell`. dart:io's flag is designed for
  // `Process.start('ls', ['-l'], runInShell: true)` and quotes the executable
  // before handing it to the shell, so a raw command line comes back as exit
  // 127 -- the shell looks for a program literally named `exit 5`. Building the
  // invocation here also makes the shell Kryon uses visible in the source,
  // which spec/execution.md requires it to document.
  final resolved = shell
      ? (_windows
          ? [
              io.Platform.environment['COMSPEC'] ?? 'cmd.exe',
              '/d',
              '/s',
              '/c',
              executable
            ]
          : ['/bin/sh', '-c', executable])
      : [executable, ...arguments];

  try {
    return await io.Process.start(
      resolved.first,
      resolved.sublist(1),
      workingDirectory: options.cwd,
      environment: environment,
      // When we hand over an explicit map we have already merged the parent
      // environment into it ourselves. Letting dart:io add the parent again
      // would silently resurrect every variable the caller asked to remove.
      includeParentEnvironment: environment == null,
    );
  } on io.ProcessException catch (error) {
    throw _mapStartError(error, executable);
  }
}

KryonException _mapStartError(io.ProcessException error, String executable) {
  // errno 2 / Windows error 2 is "not found"; 13 / 5 is "access denied".
  return switch (error.errorCode) {
    2 || 3 => CommandNotFoundException("executable not found: '$executable'"),
    5 || 13 => PermissionDeniedException(
        "not permitted to execute '$executable': ${error.message}"),
    _ => ProcessStartFailedException(
        "could not start '$executable': ${error.message}"),
  };
}

typedef _Outcome = ({
  TerminationReason termination,
  int? exitCode,
  int? signal
});

/// Map a raw exit code plus any Kryon intervention to the reported outcome.
///
/// A negative code is how POSIX reports a signal. Windows never produces one,
/// which is why `signal` is always null there.
_Outcome _classify(int code, TerminationReason? reason) {
  final signaled = !_windows && code < 0;
  return (
    termination: reason ??
        (signaled ? TerminationReason.signaled : TerminationReason.exited),
    exitCode: signaled ? null : code,
    signal: signaled ? -code : null,
  );
}

Object _decode(List<int> bytes, Encoding? encoding) {
  if (encoding == null) return bytes;
  if (encoding == utf8) return utf8.decode(bytes, allowMalformed: true);
  return encoding.decode(bytes);
}

void _writeStdin(io.Process process, ExecutionOptions options) {
  final data = options.stdin;
  unawaited(() async {
    try {
      if (data != null) {
        process.stdin.add(
          data is String
              ? (options.encoding ?? utf8).encode(data)
              : data as List<int>,
        );
      }
      await process.stdin.close();
    } catch (_) {
      // The child exited before reading its input; its exit code is the story.
    }
  }());
}

/// Terminate politely, then kill. The single termination path in this SDK.
///
/// On Windows both steps are `TerminateProcess`: there is no graceful stop, and
/// the child gets no chance to flush.
Future<void> _stop(io.Process process, Duration killGrace) async {
  process.kill(io.ProcessSignal.sigterm);
  try {
    await process.exitCode.timeout(killGrace);
    return;
  } on TimeoutException {
    // Ignored it. Escalate.
  }
  process.kill(io.ProcessSignal.sigkill);
  try {
    await process.exitCode.timeout(killGrace);
  } on TimeoutException {
    // Unkillable. Nothing further this library can do.
  }
}

/// First-writer-wins holder for why Kryon intervened.
class _Reason {
  TerminationReason? _value;

  bool set(TerminationReason reason) {
    if (_value != null) return false;
    _value = reason;
    return true;
  }

  TerminationReason? get value => _value;
}

/// A byte sink that stops growing at a limit and remembers that it did.
class _Sink {
  _Sink(this.limit);

  final int? limit;
  final List<int> _bytes = [];
  bool truncated = false;

  /// Append [data]. Returns true when the limit has just been exceeded.
  ///
  /// Data past the limit is dropped rather than counted: the point of a cap is
  /// not to hold the bytes.
  bool add(List<int> data) {
    final cap = limit;
    if (cap == null) {
      _bytes.addAll(data);
      return false;
    }
    final room = cap - _bytes.length;
    if (room > 0) {
      _bytes.addAll(data.length <= room ? data : data.sublist(0, room));
    }
    if (data.length > room) {
      truncated = true;
      return true;
    }
    return false;
  }

  List<int> value() => _bytes;
}
