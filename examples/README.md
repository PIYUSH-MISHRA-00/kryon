# Examples

Every example here runs. Snippets that only exist in prose rot silently; files that run get
noticed when they break.

## Python

```bash
pip install -e ../python          # or: pip install kryon
python examples/python/01_execute.py
```

| File | Shows |
|---|---|
| [`01_execute.py`](python/01_execute.py) | Running a command, exit codes, `check`, and why a missing executable raises |
| [`02_environment_and_cwd.py`](python/02_environment_and_cwd.py) | Inheriting, merging, removing and allowlisting environment variables; working directories |
| [`03_streaming.py`](python/03_streaming.py) | Output as it is produced, both streams tagged, and backpressure |
| [`04_interactive.py`](python/04_interactive.py) | A conversation with a long-lived process |
| [`05_limits.py`](python/05_limits.py) | Timeouts, output caps, and what termination reasons mean |
| [`06_async.py`](python/06_async.py) | Concurrent execution and cancellation that actually kills the child |

## TypeScript

```bash
cd javascript && npm install && npm run build
node ../examples/typescript/tour.mjs
```

| File | Shows |
|---|---|
| [`tour.mjs`](typescript/tour.mjs) | The whole API in one file: execution, the failure model, limits, streaming with timestamps, and `AbortSignal` cancellation |

## Dart

```bash
cd dart && dart pub get
dart run example/kryon_example.dart
```

Lives at [`dart/example/kryon_example.dart`](../dart/example/kryon_example.dart) rather than here,
because pub.dev looks for an `example/` directory inside the package and scores it.

## Java and Kotlin

Their [README](../java/README.md) [files](../kotlin/README.md) carry the equivalent snippets. There
is no separate example directory for them yet — a Gradle project whose only purpose is to hold six
`main` methods is more build configuration than example, and the READMEs are what people actually
read first.

If you would find runnable JVM examples useful,
[say so](https://github.com/PIYUSH-MISHRA-00/kryon/discussions) and they will get written.

## Architecture

| File | Shows |
|---|---|
| [`web-terminal-architecture.md`](web-terminal-architecture.md) | How a browser-facing terminal must be built — and why the easy version is not in this directory |

## What you will not find here

**A runnable web terminal server.** The version that is fifteen lines and demos beautifully is
remote code execution as a service. The architecture document explains what the real thing
requires, and Kryon will not ship the shortcut as an example.

**Anything that depends on a feature that does not exist.** No PTY examples, no terminal-emulator
examples, no transport examples — those layers are specified and not implemented, and an example
is a claim.

## A reminder

Kryon is not a sandbox. These examples run commands with your privileges. Before adapting any of
them to handle input you do not control, read the
[threat model](../docs/security/threat-model.md).
