/// Runs the shared conformance corpus against the Dart SDK.
///
/// The corpus lives at `tests/conformance/cases.json` in the repository root
/// and is language-neutral. This file is the Dart *runner*: it maps each case
/// onto this SDK's API and asserts the expectations. Every SDK writes one of
/// these; the corpus itself is never forked.
library;

import 'dart:convert';
import 'dart:io';

import 'package:kryon/kryon.dart';
import 'package:test/test.dart';

final bool _windows = Platform.isWindows;

/// The repository-root corpus, shared verbatim with every other SDK.
final File _corpusFile = File('../tests/conformance/cases.json');

final Map<String, dynamic> _corpus =
    jsonDecode(_corpusFile.readAsStringSync()) as Map<String, dynamic>;

final List<Map<String, dynamic>> _cases =
    (_corpus['cases'] as List<dynamic>).cast<Map<String, dynamic>>();

final Directory _scratch =
    Directory.systemTemp.createTempSync('kryon-conformance-');

/// The helper, compiled once to a native executable.
///
/// `dart run test/helper.dart` re-reads and re-compiles the source on every
/// invocation, which costs about a second each and turns a 70-case corpus into
/// a two-minute test run. Compiling once also makes the helper a genuinely
/// standalone program, which is closer to what the contract describes.
final String _helper = _compileHelper();

String _compileHelper() {
  final output = File(
    '${_scratch.path}${Platform.pathSeparator}kryon-helper'
    '${Platform.isWindows ? '.exe' : ''}',
  ).path;
  final result = Process.runSync(
    Platform.resolvedExecutable,
    ['compile', 'exe', 'test/helper.dart', '-o', output],
  );
  if (result.exitCode != 0) {
    throw StateError('could not compile the conformance helper: '
        '${result.stdout}${result.stderr}');
  }
  return output;
}

bool _applies(Map<String, dynamic> kase) {
  final platforms = kase['platforms'] as List<dynamic>?;
  if (platforms == null) return true;
  return platforms.contains(_windows ? 'windows' : 'posix');
}

/// Why a case cannot run here, or null if it can.
///
/// Dart cannot mutate its own process environment at runtime, so a case with
/// `setup_env` needs that variable supplied by whatever launched the tests.
/// Per `spec/conformance.md`, an SDK that cannot satisfy a case reports it as
/// skipped with a reason rather than quietly dropping it -- the count of skips
/// is the honest measure of where an SDK stands.
String? _skipReason(Map<String, dynamic> kase) {
  if (!_applies(kase)) return 'not applicable to this platform';

  final setup = (kase['setup_env'] as Map<String, dynamic>?) ?? const {};
  for (final entry in setup.entries) {
    if (Platform.environment[entry.key] != entry.value) {
      return 'needs ${entry.key}=${entry.value} in the environment of the test '
          'runner itself; Dart cannot set its own environment. Run with it set '
          '(CI does).';
    }
  }
  return null;
}

String _substitute(String value) =>
    value.replaceAll(r'${TMPDIR}', _scratch.resolveSymbolicLinksSync());

/// Translate corpus options into this SDK's spelling.
///
/// The corpus stores durations in seconds; Dart's idiom is [Duration]. The
/// semantics are identical, and each SDK converts in its own runner -- exactly
/// the kind of difference `spec/conformance.md` expects a runner to absorb.
ExecutionOptions _toOptions(Map<String, dynamic> kase) {
  final source = (kase['options'] as Map<String, dynamic>?) ?? const {};

  Duration? duration(String key) {
    final value = source[key];
    if (value == null) return null;
    return Duration(microseconds: ((value as num) * 1000000).round());
  }

  final rawEnv = source['clear_env'] == null && source['env'] == null
      ? const <String, String?>{}
      : ((source['env'] as Map<String, dynamic>?) ?? const {})
          .map((key, value) => MapEntry(key, value as String?));

  return ExecutionOptions(
    cwd: source['cwd'] == null ? null : _substitute(source['cwd'] as String),
    env: rawEnv,
    clearEnv: source['clear_env'] as bool?,
    stdin: kase['stdin'] as String?,
    timeout: duration('timeout'),
    killGrace: duration('kill_grace'),
    maxOutputBytes: source['max_output_bytes'] as int?,
    encoding: source['encoding'] == null ? null : utf8,
    check: source['check'] as bool?,
  );
}

({String executable, List<String>? arguments}) _toCommand(
  Map<String, dynamic> kase,
) {
  if (kase.containsKey('shell_command')) {
    final shell = kase['shell_command'] as Map<String, dynamic>;
    return (
      executable: shell[_windows ? 'windows' : 'posix'] as String,
      arguments: null,
    );
  }
  final args = ((kase['args'] as List<dynamic>?) ?? const [])
      .map((a) => _substitute(a as String))
      .toList();

  if (kase.containsKey('executable')) {
    return (executable: kase['executable'] as String, arguments: args);
  }
  return (executable: _helper, arguments: args);
}

Type _errorType(String name) => switch (name) {
      'CommandNotFound' => CommandNotFoundException,
      'PermissionDenied' => PermissionDeniedException,
      'ProcessStartFailed' => ProcessStartFailedException,
      'InvalidArguments' => InvalidArgumentsException,
      'ProcessFailed' => ProcessFailedException,
      'ProcessTimeout' => ProcessTimeoutException,
      'ProcessCancelled' => ProcessCancelledException,
      'ResourceLimitExceeded' => ResourceLimitExceededException,
      _ => throw StateError('unknown error name $name'),
    };

void _assertResult(Map<String, dynamic> kase, ExecutionResult result) {
  final e = kase['expect'] as Map<String, dynamic>;
  final id = kase['id'] as String;

  if (e.containsKey('exit_code')) {
    expect(result.exitCode, e['exit_code'], reason: id);
  }
  if (e.containsKey('termination')) {
    expect(result.termination.wireName, e['termination'], reason: id);
  }
  if (e.containsKey('ok')) expect(result.ok, e['ok'], reason: id);
  if (e.containsKey('stdout')) {
    expect(result.stdoutText, e['stdout'], reason: id);
  }
  if (e.containsKey('stderr')) {
    expect(result.stderrText, e['stderr'], reason: id);
  }
  if (e.containsKey('stdout_contains')) {
    expect(result.stdoutText, contains(e['stdout_contains']), reason: id);
  }
  if (e.containsKey('stderr_contains')) {
    expect(result.stderrText, contains(e['stderr_contains']), reason: id);
  }
  if (e.containsKey('stdout_truncated')) {
    expect(result.stdoutTruncated, e['stdout_truncated'], reason: id);
  }
  if (e.containsKey('stdout_bytes_at_most')) {
    final size = result.stdout is String
        ? (result.stdout as String).length
        : (result.stdout as List<int>).length;
    expect(size, lessThanOrEqualTo(e['stdout_bytes_at_most'] as int),
        reason: id);
  }
  if (e.containsKey('duration_at_most')) {
    expect(
      result.duration.inMilliseconds,
      lessThanOrEqualTo(((e['duration_at_most'] as num) * 1000).round()),
      reason: id,
    );
  }
  if (e.containsKey('duration_at_least')) {
    expect(
      result.duration.inMilliseconds,
      greaterThanOrEqualTo(((e['duration_at_least'] as num) * 1000).round()),
      reason: id,
    );
  }
  if (e.containsKey('signal_present')) {
    expect(result.signal != null, e['signal_present'], reason: id);
  }
  if (e['stdout_is_bytes'] == true) {
    expect(result.stdout, isA<List<int>>(), reason: id);
  }
  if (e.containsKey('stdout_contains_bytes')) {
    final hex = e['stdout_contains_bytes'] as String;
    final needle = <int>[
      for (var i = 0; i < hex.length; i += 2)
        int.parse(hex.substring(i, i + 2), radix: 16),
    ];
    final hay = result.stdout is String
        ? utf8.encode(result.stdout as String)
        : result.stdout as List<int>;
    expect(_indexOfBytes(hay, needle) >= 0, isTrue, reason: id);
  }
  if (e.containsKey('stdout_is_dir')) {
    final expected = Directory(_substitute(e['stdout_is_dir'] as String))
        .resolveSymbolicLinksSync();
    final actual =
        Directory(result.stdoutText.trim()).resolveSymbolicLinksSync();
    expect(actual, expected, reason: id);
  }
}

int _indexOfBytes(List<int> haystack, List<int> needle) {
  for (var i = 0; i + needle.length <= haystack.length; i++) {
    var match = true;
    for (var j = 0; j < needle.length; j++) {
      if (haystack[i + j] != needle[j]) {
        match = false;
        break;
      }
    }
    if (match) return i;
  }
  return -1;
}

void main() {
  tearDownAll(() {
    if (_scratch.existsSync()) _scratch.deleteSync(recursive: true);
  });

  final executeCases = _cases
      .where((c) => c['api'] == 'execute' || c['api'] == 'execute_shell')
      .toList();
  final spawnCases = _cases.where((c) => c['api'] == 'spawn').toList();

  group('conformance: execute', () {
    for (final kase in executeCases) {
      test(
        kase['id'] as String,
        () async {
          final runtime = Runtime();
          final command = _toCommand(kase);
          final options = _toOptions(kase);

          Future<ExecutionResult> invoke() => command.arguments == null
              ? runtime.executeShell(command.executable, options)
              : runtime.execute(
                  command.executable,
                  command.arguments!,
                  options,
                );

          final expected = (kase['expect'] as Map<String, dynamic>)['raises'];
          if (expected != null) {
            await expectLater(
              invoke,
              throwsA(isA<KryonException>().having(
                (e) => e.runtimeType,
                'type',
                _errorType(expected as String),
              )),
              reason: kase['id'] as String,
            );
            return;
          }
          _assertResult(kase, await invoke());
        },
        skip: _skipReason(kase),
        timeout: const Timeout(Duration(seconds: 120)),
      );
    }
  });

  group('conformance: spawn', () {
    for (final kase in spawnCases) {
      test(
        kase['id'] as String,
        () async {
          final runtime = Runtime();
          final command = _toCommand(kase);
          final proc = await runtime.spawn(
            command.executable,
            command.arguments!,
            _toOptions(kase),
          );
          final e = kase['expect'] as Map<String, dynamic>;

          try {
            if (kase['scope_exit_only'] == true) {
              expect(proc.running, isTrue, reason: kase['id'] as String);
              await proc.close();
              expect(proc.running, e['running_after_scope'],
                  reason: kase['id'] as String);
              return;
            }

            if (kase['terminate_after'] != null) {
              await Future<void>.delayed(Duration(
                microseconds:
                    ((kase['terminate_after'] as num) * 1000000).round(),
              ));
              proc.terminate();
              final result = await proc.wait(const Duration(seconds: 20));
              expect(proc.running, e['running_after_terminate'],
                  reason: kase['id'] as String);
              if (e.containsKey('duration_at_most')) {
                expect(
                  result.duration.inMilliseconds,
                  lessThanOrEqualTo(
                      ((e['duration_at_most'] as num) * 1000).round()),
                  reason: kase['id'] as String,
                );
              }
              return;
            }

            for (final chunk in (kase['write'] as List<dynamic>?) ?? const []) {
              proc.write(chunk as String);
            }
            if (kase['close_stdin'] == true) await proc.closeStdin();

            final chunks = await proc.output.toList();
            final result = await proc.wait(const Duration(seconds: 20));
            final text = utf8.decode(
              [for (final c in chunks) ...c.data],
              allowMalformed: true,
            );

            if (e.containsKey('streamed_chunks_at_least')) {
              expect(chunks.length,
                  greaterThanOrEqualTo(e['streamed_chunks_at_least'] as int),
                  reason: kase['id'] as String);
            }
            if (e.containsKey('stdout_contains')) {
              expect(text, contains(e['stdout_contains']),
                  reason: kase['id'] as String);
            }
            if (e.containsKey('stdout_contains_last')) {
              expect(text, contains(e['stdout_contains_last']),
                  reason: kase['id'] as String);
            }
            if (e.containsKey('exit_code')) {
              expect(result.exitCode, e['exit_code'],
                  reason: kase['id'] as String);
            }
          } finally {
            await proc.close();
          }
        },
        skip: _skipReason(kase),
        timeout: const Timeout(Duration(seconds: 120)),
      );
    }
  });

  group('corpus integrity', () {
    test('every case has a runner', () {
      final covered = {
        ...executeCases.map((c) => c['id']),
        ...spawnCases.map((c) => c['id']),
      };
      expect(covered, _cases.map((c) => c['id']).toSet(),
          reason: 'some corpus cases have no runner');
    });

    test('ids are unique', () {
      final ids = _cases.map((c) => c['id']).toList();
      expect(ids.toSet().length, ids.length,
          reason: 'duplicate case id in the corpus');
    });

    test('every case explains itself', () {
      final missing =
          _cases.where((c) => c['why'] == null).map((c) => c['id']).toList();
      expect(missing, isEmpty, reason: "cases missing a 'why'");
    });
  });
}
