/// Value types shared by every operation.
///
/// These are the Dart spelling of the conceptual objects in
/// `spec/execution.md`. They hold no resources and perform no I/O, which is
/// what makes them safe to pass around, log and compare.
library;

import 'dart:convert';

import 'errors.dart';

/// Which pipe a chunk of output arrived on.
///
/// Named `OutputStream` rather than `Stream` on purpose: a library that exports
/// a type called `Stream` shadows `dart:async`'s `Stream` for everyone who
/// imports it, which is a hostile thing to do to your users. The specification
/// fixes semantics, not spelling.
enum OutputStream { stdout, stderr }

/// Why the process stopped.
///
/// The three Kryon-initiated reasons take precedence over the operating
/// system's own account of the death. A process killed because it exceeded its
/// timeout was, at the kernel level, [signaled] -- but [timeout] is the fact
/// the caller needs in order to react correctly, so that is what is reported.
enum TerminationReason {
  /// The process exited on its own. `exitCode` is present.
  exited('EXITED'),

  /// The process was killed by a signal Kryon did not send. POSIX only.
  signaled('SIGNALED'),

  /// The timeout elapsed and Kryon terminated the process.
  timeout('TIMEOUT'),

  /// The caller cancelled and Kryon terminated the process.
  cancelled('CANCELLED'),

  /// `maxOutputBytes` was exceeded and Kryon stopped the process.
  outputLimit('OUTPUT_LIMIT');

  const TerminationReason(this.wireName);

  /// The name used in the cross-language conformance corpus.
  final String wireName;
}

/// One chunk of output, tagged with the pipe it arrived on.
class OutputChunk {
  const OutputChunk(this.stream, this.data);

  final OutputStream stream;
  final List<int> data;

  @override
  String toString() => 'OutputChunk(${stream.name}, ${data.length} bytes)';
}

/// Options for a single execution.
///
/// A [Runtime] carries defaults and each call may override them with
/// [mergedWith]; `env` merges, everything else is replaced.
class ExecutionOptions {
  const ExecutionOptions({
    this.cwd,
    this.env = const {},
    bool? clearEnv,
    this.stdin,
    this.timeout,
    this.maxOutputBytes,
    this.encoding,
    bool? check,
    Duration? killGrace,
  })  : _clearEnv = clearEnv,
        _check = check,
        _killGrace = killGrace;

  // Stored nullable so that "not specified" is distinguishable from "specified
  // as false". Without that distinction a per-call override could turn a flag
  // on but never off, which is the kind of asymmetry nobody discovers until it
  // costs them an afternoon.
  final bool? _clearEnv;
  final bool? _check;
  final Duration? _killGrace;

  /// Working directory. Inherited when null. A path that is not a directory is
  /// an error, never a silent fallback to the current directory.
  final String? cwd;

  /// Variables merged *over* the inherited environment. A null value removes
  /// the variable. To control the environment strictly, combine with
  /// [clearEnv].
  final Map<String, String?> env;

  /// Start from an empty environment instead of inheriting one. With [env],
  /// this is an allowlist. On Windows, `SystemRoot` and `SystemDrive` are still
  /// preserved, because many binaries fail to start without them.
  bool get clearEnv => _clearEnv ?? false;

  /// Data written to the child's stdin, after which stdin is closed.
  final Object? stdin;

  /// Wall-clock limit. On expiry the process is terminated politely, then
  /// killed after [killGrace]. Output collected so far is kept.
  final Duration? timeout;

  /// Per-stream cap in bytes, counted before decoding. Exceeding it stops the
  /// process and sets the matching `*Truncated` flag.
  final int? maxOutputBytes;

  /// When set, output is decoded with this codec; when null, output is bytes.
  ///
  /// Decoding is lossy by design: an output cap can cut a multi-byte character
  /// in half, and throwing on that would turn a truncation into a crash.
  final Encoding? encoding;

  /// Throw instead of returning when the result is not successful.
  bool get check => _check ?? false;

  /// Time between the polite stop and the forced kill.
  Duration get killGrace => _killGrace ?? const Duration(seconds: 5);

  /// Return a copy with [overrides] applied. `env` merges rather than replaces.
  ExecutionOptions mergedWith(ExecutionOptions? overrides) {
    if (overrides == null) return this;
    return ExecutionOptions(
      cwd: overrides.cwd ?? cwd,
      env: {...env, ...overrides.env},
      clearEnv: overrides._clearEnv ?? _clearEnv,
      stdin: overrides.stdin ?? stdin,
      timeout: overrides.timeout ?? timeout,
      maxOutputBytes: overrides.maxOutputBytes ?? maxOutputBytes,
      encoding: overrides.encoding ?? encoding,
      check: overrides._check ?? _check,
      killGrace: overrides._killGrace ?? _killGrace,
    );
  }

  /// Reject a malformed request before anything is spawned.
  void validate() {
    if (timeout != null && timeout! <= Duration.zero) {
      throw InvalidArgumentsException('timeout must be positive, got $timeout');
    }
    if (maxOutputBytes != null && maxOutputBytes! <= 0) {
      throw InvalidArgumentsException(
        'maxOutputBytes must be positive, got $maxOutputBytes',
      );
    }
    if (killGrace < Duration.zero) {
      throw InvalidArgumentsException(
        'killGrace must not be negative, got $killGrace',
      );
    }
  }
}

/// What happened when a process ran.
///
/// A result exists only for a process that actually started. Failures to start
/// throw; see `errors.dart`.
class ExecutionResult {
  const ExecutionResult({
    required this.executable,
    required this.arguments,
    required this.exitCode,
    required this.signal,
    required this.stdout,
    required this.stderr,
    required this.duration,
    required this.termination,
    this.pid,
    this.stdoutTruncated = false,
    this.stderrTruncated = false,
  });

  final String executable;
  final List<String> arguments;

  /// Integer exit status, or null if the process did not exit normally.
  final int? exitCode;

  /// The terminating signal where the platform reports one. Always null on
  /// Windows.
  final int? signal;

  /// Captured standard output: `List<int>` bytes, or a [String] when an
  /// encoding was set.
  final Object stdout;

  /// Captured standard error.
  final Object stderr;

  final Duration duration;
  final TerminationReason termination;
  final int? pid;
  final bool stdoutTruncated;
  final bool stderrTruncated;

  /// True only for a process that exited on its own with status `0`.
  bool get ok => termination == TerminationReason.exited && exitCode == 0;

  /// [stdout] as text, decoding bytes as UTF-8 if it is not already a string.
  String get stdoutText => _asText(stdout);

  /// [stderr] as text, decoding bytes as UTF-8 if it is not already a string.
  String get stderrText => _asText(stderr);

  /// Return this result if successful, otherwise throw the matching error.
  ///
  /// This is what `check: true` calls. Useful on its own when you want to
  /// inspect a result first and only then insist it succeeded.
  ExecutionResult checked() {
    if (ok) return this;
    final detail = _stderrExcerpt();
    return switch (termination) {
      TerminationReason.timeout =>
        throw ProcessTimeoutException("'$executable' timed out$detail", this),
      TerminationReason.cancelled => throw ProcessCancelledException(
          "'$executable' was cancelled$detail", this),
      TerminationReason.outputLimit => throw ResourceLimitExceededException(
          "'$executable' exceeded its output limit$detail", this),
      TerminationReason.signaled => throw ProcessFailedException(
          "'$executable' was killed by signal $signal$detail", this),
      TerminationReason.exited => throw ProcessFailedException(
          "'$executable' exited with code $exitCode$detail", this),
    };
  }

  /// A short stderr excerpt for the error message.
  ///
  /// Deliberately capped and deliberately stderr-only: environments and stdin
  /// routinely hold credentials, and an error message is the most-pasted string
  /// a library produces.
  String _stderrExcerpt() {
    final text = stderrText.trim();
    if (text.isEmpty) return '';
    final excerpt = text.length > 500 ? '${text.substring(0, 500)}...' : text;
    return '\nstderr: $excerpt';
  }

  @override
  String toString() => 'ExecutionResult(executable: $executable, '
      'exitCode: $exitCode, termination: ${termination.wireName}, '
      'duration: ${duration.inMilliseconds}ms, '
      'stdout: ${_size(stdout)}, stderr: ${_size(stderr)})';
}

String _asText(Object value) => value is String
    ? value
    : utf8.decode(value as List<int>, allowMalformed: true);

String _size(Object value) => value is String
    ? '${value.length} chars'
    : '${(value as List<int>).length} bytes';
