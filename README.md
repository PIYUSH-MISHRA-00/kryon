<div align="center">

# Kryon

**Powerful terminal execution, everywhere.**

Run operating-system commands, drive interactive processes, and stream their output —
with one conceptual API across Python, TypeScript, Dart, Java and Kotlin.

[![CI](https://github.com/PIYUSH-MISHRA-00/kryon/actions/workflows/ci.yml/badge.svg)](https://github.com/PIYUSH-MISHRA-00/kryon/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![PyPI](https://img.shields.io/pypi/v/kryon.svg)](https://pypi.org/project/kryon/)
[![Version](https://img.shields.io/badge/version-1.0.0-brightgreen.svg)](CHANGELOG.md)

[Website](https://piyush-mishra-00.github.io/kryon/) · [Documentation](docs/) · [Specification](spec/) · [Security](docs/security/threat-model.md) · [Roadmap](ROADMAP.md) · [Contributing](CONTRIBUTING.md)

</div>

---

> ### Project status
>
> **`1.0.0`.** All **five SDKs** implement command execution and process streaming, and all five
> pass the same [conformance corpus](tests/conformance/cases.json) on Linux, macOS and Windows.
>
> **PTY, terminal emulation and remote transports are specified and not implemented.** They are
> `1.x` work — see the [roadmap](ROADMAP.md). Nothing in this README describes a feature that does
> not exist; where something is planned, it says so.
>
> **All five SDKs are published** — PyPI, npm, pub.dev and Maven Central (twice). The
> [status table](#install) says exactly where each one lives.
>
> On npm the package is **`kryon-exec`**: the registry refuses `kryon` as too similar to the
> existing `cron` package. That is a typosquatting filter, not a name clash — nobody holds
> `kryon` there, and nobody can publish it.

## What it does

```python
from kryon import Runtime

runtime = Runtime(encoding="utf-8", timeout=30)

# Run it and tell me what happened.
result = runtime.execute("git", ["status", "--porcelain"])
print(result.stdout, result.exit_code, result.ok)

# Start it and let me talk to it.
with runtime.spawn("pip", ["install", "numpy"]) as proc:
    for stream, chunk in proc:
        print(chunk.decode(), end="")
```

The same two operations, in each language's own idiom:

<table>
<tr><td>

```ts
// TypeScript
const result = await runtime.execute(
  "git", ["status", "--porcelain"]);

await using proc = await runtime.spawn("npm", ["ci"]);
for await (const { data } of proc.output) { … }
```

</td><td>

```kotlin
// Kotlin
val result = runtime.execute(
    "git", listOf("status", "--porcelain"))

runtime.spawn("gradle", listOf("build")).use { proc ->
    proc.output.collect { print(it.text()) }
}
```

</td></tr>
<tr><td>

```dart
// Dart
final result = await runtime.execute(
    'git', ['status', '--porcelain']);

final proc = await runtime.spawn('dart', ['run', 'w.dart']);
await for (final chunk in proc.output) { … }
```

</td><td>

```java
// Java
var result = runtime.execute(
        "git", List.of("status", "--porcelain"));

try (var proc = runtime.spawn("mvn", List.of("verify"))) {
    for (var chunk : proc.output()) { … }
}
```

</td></tr>
</table>

## Why it exists

Running a command is easy in every language. Doing it *well* is not, and every ecosystem
re-solves the same problems badly — the convenient API hands your string to a shell, the timeout
returns without killing anything, a chatty command exhausts memory, and the error throws away the
stderr that explained it.

Kryon is not "a better `subprocess`". It is **one well-specified execution model**, implemented
natively in five languages, verified by one shared test corpus.

**If you run one command occasionally in one language, use your standard library.** That is the
honest answer, and [it is written down](docs/guides/why-kryon.md). Kryon earns its place when you
are streaming, enforcing real limits, handling untrusted input, or doing any of it from more than
one language.

## What makes it different

### The dangerous thing has a different name

```python
runtime.execute("wc", ["-l", user_input])        # safe — one literal argument, no shell
runtime.execute_shell(f"wc -l {user_input}")     # command injection, and it looks like it
```

There is no `shell=True` flag in any SDK, and
[the specification forbids one](spec/execution.md#2-argument-vector-execution-is-the-default).
A boolean among a dozen options is easy to set by accident and easy to miss in review; a
different method name is visible in every diff.

### Failing to start is an error. Failing while running is a result.

A missing executable raises — nothing ran, so there is nothing to report. A process that exited
`1` returns a result, because `grep` exits `1` to mean "no match" and raising on that makes
ordinary code wrong by default. `check` opts into the strict style, and every error it raises
carries the result it came from, stderr included.

### Limits that actually hold

Timeouts terminate, wait a documented grace period, then kill — including a process that ignores
`SIGTERM`. Output caps bound memory *during* the flood rather than after buffering everything.
Leaving a scope, cancelling a task, or a timeout firing all terminate the child **before** control
returns. Kryon never hands you back the flow of execution while leaving a process running.

### Five SDKs, one behaviour — enforced, not promised

Every SDK runs the same [conformance corpus](tests/conformance/cases.json): one JSON file at the
repository root, never forked per language, where each case records *what would break in the real
world* if it regressed. An SDK that cannot satisfy a case skips it with a reason — it never
quietly drops it. [How that works](docs/architecture/sdk-design.md).

### Nearly zero dependencies

Python, TypeScript, Dart and Java have **no runtime dependencies at all**. Kotlin has exactly one
— `kotlinx-coroutines-core` — because coroutines are how asynchronous Kotlin is written.

## Architecture

```
┌──────────────────────────────────────────────────────────────┐
│ 5. Renderer            web · Flutter · JVM · native          │  planned
├──────────────────────────────────────────────────────────────┤
│ 4. Terminal emulator   ANSI/VT parser + screen state         │  specified
│    Pure state machine. Bytes in, screen out. No I/O.         │
├──────────────────────────────────────────────────────────────┤
│ 3. Transport           local · WebSocket · SSH               │  specified
├──────────────────────────────────────────────────────────────┤
│ 2. PTY engine          openpty · ConPTY                      │  specified
├──────────────────────────────────────────────────────────────┤
│ 1. Execution engine    spawn · streams · signals · lifecycle │  IMPLEMENTED × 5
└──────────────────────────────────────────────────────────────┘
```

Each layer is useful without the ones above it. Running `git status` needs no terminal. A web
terminal that renders with an existing component needs a PTY but not an emulator. Turning a
recorded CI log into a rendered screen needs the emulator and nothing else.

[Full architecture](docs/architecture/overview.md).

## Install

| Ecosystem | Install | Status |
|---|---|---|
| **Python** | `pip install kryon` | ✅ **Published to PyPI** |
| **TypeScript** | `npm install kryon-exec` | ✅ **Published to npm** |
| **Dart** | `dart pub add kryon` | ✅ **Published to pub.dev** |
| **Java** | `io.github.piyush-mishra-00:kryon:1.0.0` | ✅ **Published to Maven Central** |
| **Kotlin** | `io.github.piyush-mishra-00:kryon-kotlin:1.0.0` | ✅ **Published to Maven Central** |

Every artifact on Maven Central is GPG-signed with key `704CD5A4984CD865`, which is on
keyserver.ubuntu.com and keys.openpgp.org — verify before you trust. See
[releases](docs/development/releases.md) for how each one is published. To build from source
instead:

```bash
git clone https://github.com/PIYUSH-MISHRA-00/kryon.git
```

## Security

**Kryon is not a sandbox.** It runs what you tell it to run, with the privileges you already
have. Its timeouts and output caps are resource management — they keep *your* process healthy;
they do not contain a hostile one. Isolation is the job of a container, a VM or an unprivileged
account.

Three rules, expanded in the [threat model](docs/security/threat-model.md):

1. Never build an `execute_shell` string from untrusted input.
2. Never let untrusted input choose the executable.
3. Never expose command execution to a browser without authentication, authorization, a command
   allowlist **and** OS-level isolation — [all four](docs/security/remote-execution.md).

Report vulnerabilities privately: [Security advisories](https://github.com/PIYUSH-MISHRA-00/kryon/security/advisories/new).
Never in a public issue. See [`SECURITY.md`](SECURITY.md).

## Platform support

| | Linux | macOS | Windows | Android | iOS | Browser |
|---|---|---|---|---|---|---|
| Process execution | ✅ | ✅ | ✅ | Planned | ❌ Not possible | ❌ Needs a backend |
| Streaming, limits, cancellation | ✅ | ✅ | ✅ | Planned | ❌ | ❌ |
| `terminate()` | ✅ `SIGTERM` | ✅ `SIGTERM` | ⚠️ No graceful stop | Planned | ❌ | ❌ |
| Arbitrary signals | ✅ (Python, Dart) | ✅ | ❌ No signals exist | Planned | ❌ | ❌ |
| PTY | ⬜ Planned | ⬜ Planned | ⬜ Planned (ConPTY) | ⬜ Planned | ❌ | ❌ Backend |
| Terminal emulation | ⬜ Planned | ⬜ Planned | ⬜ Planned | ⬜ Planned | ⬜ Planned | ⬜ Planned |

Windows has no `SIGTERM`: `terminate()` there is the same operation as `kill()`, and the child
gets no chance to flush. On the JVM, `signal()` supports only `SIGTERM` and `SIGKILL` because the
JDK exposes nothing else. iOS does not permit spawning child processes at all — a platform rule,
not a missing feature.

[Full matrix and the details](docs/guides/platform-support.md).

## Documentation

| | |
|---|---|
| [Overview](docs/getting-started/overview.md) | What Kryon is and is not |
| [Your first commands](docs/getting-started/first-terminal.md) | From one command to a live stream |
| [Architecture](docs/architecture/overview.md) | The five layers and why they are separate |
| [SDK design](docs/architecture/sdk-design.md) | How five SDKs stay one product |
| [Threat model](docs/security/threat-model.md) | Read before deploying anything |
| [Why Kryon?](docs/guides/why-kryon.md) | Honest comparison, including when not to use it |
| [Specification](spec/README.md) | The normative language-neutral contract |
| [Examples](examples/) | Runnable, not illustrative |

Per-SDK reference: [Python](python/README.md) · [TypeScript](javascript/README.md) ·
[Dart](dart/README.md) · [Java](java/README.md) · [Kotlin](kotlin/README.md)

## Roadmap

Phases 0–8 are done: foundation, specification, conformance, and all five SDKs at execution
parity. Next: **PTY on POSIX**, then ConPTY, then the terminal emulator.

Full plan, honestly scoped, in [`ROADMAP.md`](ROADMAP.md).

## Contributing

Contributions are welcome. Start with [`CONTRIBUTING.md`](CONTRIBUTING.md), the
[development setup](docs/development/setup.md), and the
[testing guide](docs/development/testing.md).

- [Discussions](https://github.com/PIYUSH-MISHRA-00/kryon/discussions) — questions and ideas
- [Issues](https://github.com/PIYUSH-MISHRA-00/kryon/issues) — bugs and proposals
- [`SUPPORT.md`](SUPPORT.md) — which is which
- [`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md) — how we behave here

## Support the project

Kryon is built and maintained by one person, in their own time, and given away under Apache-2.0.
If it saves you an afternoon, you can
[**buy me a coffee** ☕](https://buymeacoffee.com/piyushmishra00).

Entirely optional, and it changes nothing about the licence, the roadmap or how issues are
triaged.

## License

[Apache-2.0](LICENSE).
