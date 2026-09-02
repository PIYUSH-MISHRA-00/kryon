/// Unit tests for behaviour the shared conformance corpus does not reach.
///
/// The corpus covers cross-language semantics. This file covers the Dart
/// surface itself: option merging, error mapping, the guards on
/// [KryonProcess], and the promise that nothing is left running when a caller
/// walks away.
library;

import 'dart:convert';
import 'dart:io';

import 'package:kryon/kryon.dart';
import 'package:test/test.dart';

final bool _windows = Platform.isWindows;

final Directory _scratch = Directory.systemTemp.createTempSync('kryon-api-');

/// Compiled once; `dart run` would re-compile on every invocation.
final String _helper = () {
  final output =
      '${_scratch.path}${Platform.pathSeparator}helper${_windows ? '.exe' : ''}';
  final result = Process.runSync(
    Platform.resolvedExecutable,
    ['compile', 'exe', 'test/helper.dart', '-o', output],
  );
  if (result.exitCode != 0) {
    throw StateError('could not compile helper: ${result.stderr}');
  }
  return output;
}();

bool _alive(int pid) {
  if (_windows) return false; // no cheap probe; POSIX-only assertion
  return Process.runSync('kill', ['-0', '$pid']).exitCode == 0;
}

ExecutionResult _result({
  int? exitCode = 0,
  TerminationReason termination = TerminationReason.exited,
  Object stderr = const <int>[],
}) =>
    ExecutionResult(
      executable: 'prog',
      arguments: const [],
      exitCode: exitCode,
      signal: null,
      stdout: const <int>[],
      stderr: stderr,
      duration: const Duration(milliseconds: 10),
      termination: termination,
    );

void main() {
  tearDownAll(() {
    if (_scratch.existsSync()) _scratch.deleteSync(recursive: true);
  });

  group('options', () {
    test('runtime defaults apply to calls', () async {
      final runtime = Runtime(const ExecutionOptions(encoding: utf8));
      final result = await runtime.execute(_helper, ['echo', 'hi']);
      expect(result.stdout, 'hi\n');
    });

    test('a call overrides a runtime default', () async {
      final runtime = Runtime(
        const ExecutionOptions(timeout: Duration(seconds: 30)),
      );
      final merged = runtime.defaults
          .mergedWith(const ExecutionOptions(timeout: Duration(seconds: 1)));
      expect(merged.timeout, const Duration(seconds: 1));
    });

    test('env merges rather than replaces', () async {
      final runtime = Runtime(
        const ExecutionOptions(encoding: utf8, env: {'KRYON_A': '1'}),
      );
      final result = await runtime.execute(
        _helper,
        ['env', 'KRYON_A'],
        const ExecutionOptions(env: {'KRYON_B': '2'}),
      );
      expect(result.stdout, '1\n',
          reason: "a per-call env must not drop the runtime's env");
    });

    test('a boolean override can turn a default off again', () {
      const defaults = ExecutionOptions(check: true, clearEnv: true);
      final merged = defaults
          .mergedWith(const ExecutionOptions(check: false, clearEnv: false));
      expect(merged.check, isFalse);
      expect(merged.clearEnv, isFalse);
    });

    test('an unspecified boolean leaves the default alone', () {
      const defaults = ExecutionOptions(check: true);
      final merged = defaults.mergedWith(const ExecutionOptions());
      expect(merged.check, isTrue);
    });

    test('a non-positive timeout is rejected up front', () {
      expect(
        () => const ExecutionOptions(timeout: Duration.zero).validate(),
        throwsA(isA<InvalidArgumentsException>()),
      );
    });

    test('a non-positive output limit is rejected up front', () {
      expect(
        () => const ExecutionOptions(maxOutputBytes: 0).validate(),
        throwsA(isA<InvalidArgumentsException>()),
      );
    });
  });

  group('argument validation', () {
    test('an empty executable is rejected', () {
      expect(
        () => Runtime().execute(''),
        throwsA(isA<InvalidArgumentsException>()),
      );
    });
  });

  group('results and errors', () {
    test('ok requires both exited and zero', () {
      expect(_result().ok, isTrue);
      expect(_result(exitCode: 1).ok, isFalse);
      expect(_result(termination: TerminationReason.timeout).ok, isFalse);
    });

    test('checked maps each termination to its own error', () {
      final cases = <TerminationReason, Matcher>{
        TerminationReason.timeout: isA<ProcessTimeoutException>(),
        TerminationReason.cancelled: isA<ProcessCancelledException>(),
        TerminationReason.outputLimit: isA<ResourceLimitExceededException>(),
        TerminationReason.signaled: isA<ProcessFailedException>(),
      };
      cases.forEach((termination, matcher) {
        expect(
          () => _result(exitCode: null, termination: termination).checked(),
          throwsA(matcher),
          reason: termination.wireName,
        );
      });
    });

    test('checked returns the result on success', () {
      final result = _result();
      expect(result.checked(), same(result));
    });

    test('errors carry the result they came from', () {
      try {
        _result(exitCode: 2, stderr: utf8.encode('the real reason\n'))
            .checked();
        fail('expected a throw');
      } on ProcessFailedException catch (error) {
        expect(error.result?.exitCode, 2);
        expect(error.message, contains('the real reason'),
            reason: 'stderr belongs in the message');
      }
    });

    test('the error message excerpt is capped', () {
      try {
        _result(exitCode: 1, stderr: List<int>.filled(5000, 0x78)).checked();
        fail('expected a throw');
      } on ProcessFailedException catch (error) {
        expect(error.message.length, lessThan(1000),
            reason: 'an error message is not a log file');
      }
    });

    test('a missing executable throws rather than returning a result', () {
      expect(
        () => Runtime().execute('kryon-no-such-executable-xyzzy'),
        throwsA(isA<CommandNotFoundException>()),
      );
    });
  });

  group('process', () {
    test('output can only be listened to once', () async {
      final proc = await Runtime().spawn(_helper, ['echo', 'x']);
      try {
        await proc.output.toList();
        expect(() => proc.output.listen((_) {}), throwsStateError);
      } finally {
        await proc.close();
      }
    });

    test('write after closeStdin throws', () async {
      final proc = await Runtime().spawn(_helper, ['cat']);
      try {
        await proc.closeStdin();
        expect(() => proc.write('too late\n'), throwsStateError);
      } finally {
        await proc.close();
      }
    });

    test('close is idempotent', () async {
      final proc = await Runtime().spawn(_helper, ['sleep', '30']);
      await proc.close();
      await proc.close();
      expect(proc.running, isFalse);
    });

    test('wait timeout leaves the process running', () async {
      final proc = await Runtime().spawn(_helper, ['sleep', '30']);
      try {
        await expectLater(
          proc.wait(const Duration(milliseconds: 300)),
          throwsA(isA<ProcessTimeoutException>()),
        );
        expect(proc.running, isTrue, reason: 'wait() is a wait, not a stop');
      } finally {
        await proc.close();
      }
    });

    test('signal is unsupported on Windows', () async {
      final proc = await Runtime().spawn(_helper, ['sleep', '30']);
      try {
        expect(
          () => proc.signal(ProcessSignal.sigterm),
          throwsA(isA<UnsupportedPlatformException>()),
        );
      } finally {
        await proc.close();
      }
    }, skip: _windows ? null : 'Windows-specific behaviour');

    test('signal delivers', () async {
      final proc = await Runtime().spawn(_helper, ['sleep', '30']);
      try {
        proc.signal(ProcessSignal.sigterm);
        final result = await proc.wait(const Duration(seconds: 10));
        expect(proc.running, isFalse);
        expect(result.signal, ProcessSignal.sigterm.signalNumber);
      } finally {
        await proc.close();
      }
    }, skip: _windows ? 'POSIX-specific behaviour' : null);

    test('close leaves no orphan', () async {
      final proc = await Runtime().spawn(_helper, ['sleep', '30']);
      final pid = proc.pid;
      await proc.close();
      expect(_alive(pid), isFalse);
    }, skip: _windows ? 'needs kill -0 to probe for an orphan' : null);

    test('an unconsumed process still closes promptly', () async {
      final proc = await Runtime().spawn(_helper, ['spam', '50000000']);
      final watch = Stopwatch()..start();
      await proc.close();
      expect(watch.elapsed, lessThan(const Duration(seconds: 20)),
          reason: 'close must not wait out the flood');
      expect(proc.running, isFalse);
    });

    test('chunks are tagged with their stream', () async {
      final proc = await Runtime().spawn(_helper, ['both', 'out', 'err']);
      try {
        final chunks = await proc.output.toList();
        expect(
          chunks.map((c) => c.stream).toSet(),
          {OutputStream.stdout, OutputStream.stderr},
        );
      } finally {
        await proc.close();
      }
    });
  });

  group('package', () {
    test('kryonVersion matches pubspec.yaml', () {
      final pubspec = File('pubspec.yaml').readAsStringSync();
      final match = RegExp(r'^version:\s*(\S+)', multiLine: true)
          .firstMatch(pubspec)
          ?.group(1);
      expect(kryonVersion, match,
          reason: 'a version number that is a guess is worse than none');
    });
  });
}
