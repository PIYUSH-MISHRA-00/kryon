/// The Dart implementation of the Kryon conformance helper.
///
/// Implements the verb contract in `spec/conformance.md` §2. Every SDK ships
/// one of these in its own language so that the shared corpus in
/// `tests/conformance/cases.json` means the same thing everywhere -- `echo`,
/// `sleep` and `/bin/sh` do not, and half of them do not exist on Windows.
///
/// Deliberately dependency-free and deliberately unbuffered: a helper that
/// buffers turns streaming tests into false failures.
library;

import 'dart:convert';
import 'dart:io';

Future<void> _out(String text) async {
  stdout.add(utf8.encode(text));
  await stdout.flush();
}

Future<void> _err(String text) async {
  stderr.add(utf8.encode(text));
  await stderr.flush();
}

Future<int> run(List<String> argv) async {
  if (argv.isEmpty) {
    await _err('helper: no verb given\n');
    return 64;
  }

  final verb = argv.first;
  final args = argv.sublist(1);

  switch (verb) {
    case 'echo':
      await _out('${args.join(' ')}\n');
    case 'raw':
      await _out(args.isEmpty ? '' : args.first);
    case 'err':
      await _err('${args.join(' ')}\n');
    case 'both':
      await _out('${args[0]}\n');
      await _err('${args[1]}\n');
    case 'exit':
      return int.parse(args.first);
    case 'env':
      await _out('${Platform.environment[args.first] ?? ''}\n');
    case 'dumpenv':
      final keys = Platform.environment.keys.toList()..sort();
      for (final key in keys) {
        await _out('$key=${Platform.environment[key]}\n');
      }
    case 'cwd':
      await _out('${Directory.current.path}\n');
    case 'sleep':
      await Future<void>.delayed(_seconds(args.first));
    case 'spam':
      var remaining = int.parse(args.first);
      final block = List<int>.filled(
        remaining < 65536 ? remaining : 65536,
        0x78, // 'x'
      );
      while (remaining > 0) {
        final take = remaining < block.length ? remaining : block.length;
        stdout.add(block.sublist(0, take));
        // Respect backpressure, or a large spam floods this process's own
        // memory before it ever reaches the pipe.
        await stdout.flush();
        remaining -= take;
      }
    case 'cat':
      await for (final chunk in stdin) {
        stdout.add(chunk);
        await stdout.flush();
      }
    case 'lines':
      final count = int.parse(args[0]);
      final pause = _seconds(args[1]);
      for (var n = 0; n < count; n++) {
        await _out('line $n\n');
        await Future<void>.delayed(pause);
      }
    case 'unicode':
      await _out('héllo · 世界 · \u{1F680}\n');
    case 'ansi':
      await _out('\u001b[31mred\u001b[0m\n');
    case 'ignoreterm':
      // POSIX only; on Windows there is no signal to ignore and
      // TerminateProcess wins regardless.
      if (!Platform.isWindows) {
        ProcessSignal.sigterm.watch().listen((_) {});
      }
      await Future<void>.delayed(_seconds(args.first));
    default:
      await _err("helper: unknown verb '$verb'\n");
      return 64;
  }
  return 0;
}

Duration _seconds(String value) =>
    Duration(microseconds: (double.parse(value) * 1000000).round());

Future<void> main(List<String> argv) async {
  exitCode = await run(argv);
}
