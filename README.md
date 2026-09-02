# Kryon for Dart

**Powerful terminal execution, everywhere.**

Run operating-system commands, stream their output, and manage the processes behind them —
with an API designed so the dangerous thing is the one you have to ask for by name.

[![pub](https://img.shields.io/badge/pub-kryon-0175C2.svg)](https://pub.dev/packages/kryon)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Dart](https://img.shields.io/badge/dart-%E2%89%A53.0-0175C2.svg)](pubspec.yaml)

This is the Dart SDK of [Kryon](https://github.com/PIYUSH-MISHRA-00/kryon). **Zero runtime
dependencies** — it orchestrates `dart:io` and nothing else.

> **`1.0.0`.** Command execution and process streaming are implemented and pass the
> [cross-language conformance corpus](tests/conformance/cases.json) on Linux, macOS and
> Windows. PTY, terminal emulation and remote transports are specified but **not implemented**.

## Install

```yaml
dependencies:
  kryon: ^1.0.0
```

```bash
dart pub add kryon
```

Requires Dart 3.0 or newer.

## Run something

```dart
import 'dart:convert';
import 'package:kryon/kryon.dart';

final runtime = Runtime(const ExecutionOptions(
  encoding: utf8,
  timeout: Duration(seconds: 30),
));

final result = await runtime.execute('git', ['status', '--porcelain']);

print(result.stdoutText);
print('${result.exitCode} ${result.ok} ${result.duration}');
```

## Talk to something

```dart
final proc = await runtime.spawn('dart', ['run', 'worker.dart']);
try {
  proc.write('job-1\n');
  await proc.closeStdin();

  await for (final chunk in proc.output) {
    stdout.write(utf8.decode(chunk.data, allowMalformed: true));
  }

  final result = await proc.wait();
  print('exit ${result.exitCode}');
} finally {
  // Leaving without this leaks the child. Always close in a finally.
  await proc.close();
}
```

## Two things worth knowing

### Arguments are never interpreted

```dart
await runtime.execute('wc', ['-l', userInput]);          // safe, whatever userInput is
await runtime.executeShell('wc -l $userInput');          // command injection
```

`execute` passes an argument vector to the operating system. No shell is involved, so nothing in
an argument can expand, glob, chain or substitute. Shell semantics live behind `executeShell` — a
**separate method name**, not a `runInShell: true` flag, because a boolean among a dozen options
is easy to set by accident and easy to miss in review.

### Kryon is not a sandbox

Timeouts and output caps manage resources. They do not contain a hostile program. Isolation is a
container, a VM, or an unprivileged account. See
[the threat model](docs/security/threat-model.md).

## API

### `Runtime([ExecutionOptions defaults])`

Holds default options; safe to share. Every call may override them with a third positional
`ExecutionOptions`. `env` merges with the runtime's `env`; everything else is replaced, and a
boolean set explicitly to `false` really does turn a default off.

| Option | Default | Meaning |
|---|---|---|
| `cwd` | inherited | Working directory. A path that is not a directory is an error, never a silent fallback. |
| `env` | `{}` | Variables merged over the inherited environment. `null` removes one. |
| `clearEnv` | `false` | Start from an empty environment. With `env`, this is an allowlist. |
| `stdin` | — | Data written to stdin, after which stdin is closed. |
| `timeout` | — | A `Duration`. On expiry: terminate, wait `killGrace`, kill. |
| `maxOutputBytes` | — | Per-stream cap, enforced during the flood. |
| `encoding` | — | Set it for text output, leave it for bytes. |
| `check` | `false` | Throw on an unsuccessful result. |
| `killGrace` | `5s` | Between the polite stop and the forced kill. |

### `ExecutionResult`

`executable`, `arguments`, `exitCode`, `signal`, `stdout`, `stderr`, `duration`, `termination`,
`pid`, `stdoutTruncated`, `stderrTruncated`, plus `ok`, `stdoutText`, `stderrText` and
`checked()`.

`termination` is `exited`, `signaled`, `timeout`, `cancelled` or `outputLimit`. The
Kryon-initiated reasons win over the kernel's account: a process killed for exceeding its timeout
reports `timeout`, because that is what you need in order to decide whether to retry.

### `KryonProcess`

`pid`, `running`, `exitCode`, `write()`, `closeStdin()`, `output`, `signal()`, `terminate()`,
`kill()`, `wait()`, `close()`.

`output` is a single-subscription `Stream<OutputChunk>` with real backpressure: pause it and
Kryon pauses the underlying pipes, so the child blocks instead of your heap growing.

### Errors

The rule: **failing to start is an error, failing while running is a result.**

`CommandNotFoundException`, `PermissionDeniedException`, `ProcessStartFailedException` and
`InvalidArgumentsException` throw — no process ran. `ProcessFailedException`,
`ProcessTimeoutException`, `ProcessCancelledException` and `ResourceLimitExceededException` throw
only under `check: true`, and each carries the `ExecutionResult` it came from. All implement
`KryonException`.

### A naming note

The output-stream enum is `OutputStream`, not `Stream`. A package that exports a type called
`Stream` shadows `dart:async`'s `Stream` for everyone who imports it, which is a hostile thing to
do to your users. The specification fixes semantics, not spelling.

## Platform notes

| | Linux | macOS | Windows | Android | iOS |
|---|---|---|---|---|---|
| `execute` / `spawn` | Yes | Yes | Yes | Planned | **Not possible** |
| `signal()` | Yes | Yes | `UnsupportedPlatformException` | Planned | — |
| `terminate()` | `SIGTERM` | `SIGTERM` | `TerminateProcess` — no graceful stop | Planned | — |

iOS does not permit an application to spawn arbitrary child processes. That is a platform rule,
not a missing feature; the correct architecture there is a remote transport to a server that does
the executing.

## Flutter

This package is pure Dart and has no Flutter dependency. A `kryon_flutter` package for terminal
rendering does not exist and will not be created until it has a clear purpose beyond existing.

## Develop

```bash
cd dart
dart pub get
dart analyze
dart test
dart run example/kryon_example.dart
```

Tests drive a small [helper program](dart/test/helper.dart) rather than real system commands, so they
behave the same on every platform and touch nothing outside a temporary directory.

## License

Apache-2.0. See [LICENSE](LICENSE).

---

If Kryon saves you time, you can
[buy me a coffee](https://buymeacoffee.com/piyushmishra00).
