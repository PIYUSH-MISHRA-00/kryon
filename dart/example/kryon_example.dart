/// Runnable tour of the Dart SDK.
///
///     dart run example/kryon_example.dart
library;

import 'dart:convert';
import 'dart:io';

import 'package:kryon/kryon.dart';

Future<void> main() async {
  final runtime = Runtime(const ExecutionOptions(
    encoding: utf8,
    timeout: Duration(seconds: 30),
  ));

  // ---- run it and tell me what happened ----------------------------------
  final version = await runtime.execute(
    Platform.resolvedExecutable,
    ['--version'],
  );
  stdout.writeln('exit ${version.exitCode}, ok ${version.ok}, '
      '${version.duration.inMilliseconds}ms');

  // ---- arguments are never interpreted -----------------------------------
  const hostile = r'$HOME && rm -rf / ; `whoami`';
  final literal = await runtime.execute(
    Platform.resolvedExecutable,
    ['-e', 'print(String.fromEnvironment("x"));'],
  );
  stdout.writeln('literal-safe run exited ${literal.exitCode}');
  stdout.writeln('(the string ${hostile.length} chars long would reach a '
      'program verbatim, never a shell)');

  // ---- a non-zero exit is a result, not an exception ----------------------
  final failing = await runtime.executeShell('exit 3');
  stdout.writeln('shell exit ${failing.exitCode}, ok ${failing.ok}');

  // ---- failing to start is always an error --------------------------------
  try {
    await runtime.execute('kryon-definitely-not-installed');
  } on CommandNotFoundException catch (error) {
    stdout.writeln('start failure: ${error.message}');
  }

  // ---- limits -------------------------------------------------------------
  final timedOut = await runtime.execute(
    Platform.resolvedExecutable,
    ['-e', 'import "dart:io"; void main() { sleep(Duration(seconds: 60)); }'],
    const ExecutionOptions(timeout: Duration(seconds: 1)),
  );
  stdout.writeln('termination: ${timedOut.termination.wireName} after '
      '${timedOut.duration.inMilliseconds}ms');

  // ---- streaming ----------------------------------------------------------
  final proc = await runtime.spawn(
    Platform.resolvedExecutable,
    [
      '-e',
      'import "dart:io"; Future<void> main() async { '
          'for (var n = 0; n < 3; n++) { stdout.writeln("step \$n"); '
          'await stdout.flush(); '
          'await Future<void>.delayed(const Duration(milliseconds: 200)); } }',
    ],
  );
  try {
    await for (final chunk in proc.output) {
      stdout.write(utf8.decode(chunk.data, allowMalformed: true));
    }
    final result = await proc.wait();
    stdout.writeln('streamed process exited ${result.exitCode}');
  } finally {
    // Leaving without this would leak the child. Always close in a finally.
    await proc.close();
  }

  stdout.writeln('\nKryon is not a sandbox: these limits keep your process '
      'healthy,\nthey do not contain a hostile one. '
      'See docs/security/threat-model.md.');
}
