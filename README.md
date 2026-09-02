<div align="center">

# Kryon

**Powerful terminal execution, everywhere.**

Run operating-system commands, drive interactive processes, and stream their output —
with one conceptual API being built across Python, TypeScript, Dart, Java and Kotlin.

[![CI](https://github.com/PIYUSH-MISHRA-00/kryon/actions/workflows/ci.yml/badge.svg)](https://github.com/PIYUSH-MISHRA-00/kryon/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Python](https://img.shields.io/badge/python-3.9%20%7C%203.10%20%7C%203.11%20%7C%203.12%20%7C%203.13%20%7C%203.14-blue.svg)](python/)
[![Status](https://img.shields.io/badge/status-alpha%200.1.0-orange.svg)](ROADMAP.md)

[Documentation](docs/) · [Specification](spec/) · [Security](docs/security/threat-model.md) · [Roadmap](ROADMAP.md) · [Contributing](CONTRIBUTING.md)

</div>

---

> ### Project status
>
> **`0.1.0` — alpha.** The **Python SDK** implements command execution and process
> streaming, and passes a cross-language conformance corpus on Linux, macOS and Windows.
>
> PTY, terminal emulation and remote transports are **specified and not implemented**. The
> other four SDKs are **not started**. Nothing in this README describes a feature that does
> not exist — where something is planned, it says so.
>
> Nothing is published to a package registry yet.

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

Two operations, deliberately separate: one for when you want the answer, one for when you
want a conversation.

## Why it exists

Running a command is easy in every language. Doing it *well* is not, and every ecosystem
re-solves the same problems badly — the convenient API hands your string to a shell, the
timeout returns without killing anything, a chatty command exhausts memory, and the error
throws away the stderr that explained it.

Kryon is not "a better `subprocess`". It is **one well-specified execution model**,
implemented natively in each language, with the terminal layers built on the same foundation
instead of alongside it.

**If you run one command occasionally in one language, use your standard library.** That is
the honest answer, and [it is written down](docs/guides/why-kryon.md). Kryon earns its place
when you are streaming, enforcing real limits, handling untrusted input, or doing any of it
from more than one language.

## What makes it different

### The dangerous thing has a different name

```python
runtime.execute("wc", ["-l", user_input])        # safe — one literal argument, no shell
runtime.execute_shell(f"wc -l {user_input}")     # command injection, and it looks like it
```

There is no `shell=True` flag, and [the specification forbids one](spec/execution.md#2-argument-vector-execution-is-the-default).
A boolean among a dozen options is easy to set by accident and easy to miss in review; a
different method name is visible in every diff.

### Failing to start is an error. Failing while running is a result.

A missing executable raises — nothing ran, so there is nothing to report. A process that
exited `1` returns a result, because `grep` exits `1` to mean "no match" and raising on that
makes ordinary code wrong by default. `check=True` opts into the strict style, and every
error it raises carries the result it came from, stderr included.

### Limits that actually hold

`timeout` terminates, waits a documented grace period, then kills — including a process that
ignores `SIGTERM`. `max_output_bytes` bounds memory *during* the flood rather than after
buffering everything. Leaving a `with` block, cancelling an async task, or a timeout firing
all terminate the child **before** control returns. Kryon never hands you back the flow of
execution while leaving a process running.

### Five SDKs, one behaviour — enforced, not promised

Every SDK runs the same [conformance corpus](tests/conformance/cases.json): one JSON file at
the repository root, never forked per language, where each case records *what would break in
the real world* if it regressed. An SDK that cannot satisfy a case skips it with a reason —
it never quietly drops it. [How that works](docs/architecture/sdk-design.md).

### Zero dependencies

The Python SDK depends on nothing. Adding a dependency to a package that already runs
arbitrary programs is adding supply-chain risk to the worst possible place.

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
│ 1. Execution engine    spawn · streams · signals · lifecycle │  IMPLEMENTED
└──────────────────────────────────────────────────────────────┘
```

Each layer is useful without the ones above it. Running `git status` needs no terminal. A web
terminal that renders with an existing component needs a PTY but not an emulator. Turning a
recorded CI log into a rendered screen needs the emulator and nothing else.

[Full architecture](docs/architecture/overview.md).

## Install

```bash
pip install kryon        # not yet published — see below
```

Until the first release:

```bash
pip install "kryon @ git+https://github.com/PIYUSH-MISHRA-00/kryon.git#subdirectory=python"
```

| Ecosystem | Package | Registry | Status |
|---|---|---|---|
| **Python** | `kryon` | PyPI | ✅ Implemented · not yet published |
| JavaScript / TypeScript | `kryon` | npm | ⬜ Not started |
| Dart | `kryon` | pub.dev | ⬜ Not started |
| Java | `io.github.piyush-mishra-00:kryon` | Maven Central | ⬜ Not started |
| Kotlin | `io.github.piyush-mishra-00:kryon-kotlin` | Maven Central | ⬜ Not started |

## Security

**Kryon is not a sandbox.** It runs what you tell it to run, with the privileges you already
have. Its timeouts and output caps are resource management — they keep *your* process healthy;
they do not contain a hostile one. Isolation is the job of a container, a VM or an
unprivileged account.

Three rules, expanded in the [threat model](docs/security/threat-model.md):

1. Never build an `execute_shell` string from untrusted input.
2. Never let untrusted input choose the executable.
3. Never expose command execution to a browser without authentication, authorization, a
   command allowlist **and** OS-level isolation — [all four](docs/security/remote-execution.md).

Report vulnerabilities privately: [Security advisories](https://github.com/PIYUSH-MISHRA-00/kryon/security/advisories/new).
Never in a public issue. See [`SECURITY.md`](SECURITY.md).

## Platform support

| | Linux | macOS | Windows | Android | iOS | Browser |
|---|---|---|---|---|---|---|
| Process execution | ✅ | ✅ | ✅ | Planned | ❌ Not possible | ❌ Needs a backend |
| Streaming, limits, cancellation | ✅ | ✅ | ✅ | Planned | ❌ | ❌ |
| `terminate()` | ✅ `SIGTERM` | ✅ `SIGTERM` | ⚠️ No graceful stop | Planned | ❌ | ❌ |
| Arbitrary `signal()` | ✅ | ✅ | ❌ No signals exist | Planned | ❌ | ❌ |
| PTY | ⬜ Planned | ⬜ Planned | ⬜ Planned (ConPTY) | ⬜ Planned | ❌ | ❌ Backend |
| Terminal emulation | ⬜ Planned | ⬜ Planned | ⬜ Planned | ⬜ Planned | ⬜ Planned | ⬜ Planned |

Windows has no `SIGTERM`: `terminate()` there is the same operation as `kill()`, and the
child gets no chance to flush. Kryon does not paper over that. iOS does not permit spawning
child processes at all — that is a platform rule, not a missing feature.

[Full matrix and the details](docs/guides/platform-support.md).

## Documentation

| | |
|---|---|
| [Overview](docs/getting-started/overview.md) | What Kryon is and is not |
| [Your first commands](docs/getting-started/first-terminal.md) | From one command to a live stream |
| [Architecture](docs/architecture/overview.md) | The five layers and why they are separate |
| [Threat model](docs/security/threat-model.md) | Read before deploying anything |
| [Why Kryon?](docs/guides/why-kryon.md) | Honest comparison, including when not to use it |
| [Specification](spec/README.md) | The normative language-neutral contract |
| [Python SDK](python/README.md) | API reference |
| [Examples](examples/) | Runnable, not illustrative |

## Roadmap

Phase 0 (foundation), Phase 1 (Python execution) and Phase 2 (specification) are done.
Next: **PTY on POSIX**, then TypeScript.

Full plan, honestly scoped, in [`ROADMAP.md`](ROADMAP.md).

## Contributing

Contributions are welcome — especially a second SDK, which is what turns the specification
from a document into a guarantee.

Start with [`CONTRIBUTING.md`](CONTRIBUTING.md), the
[development setup](docs/development/setup.md), and
[adding an SDK](docs/architecture/sdk-design.md#adding-an-sdk).

- [Discussions](https://github.com/PIYUSH-MISHRA-00/kryon/discussions) — questions and ideas
- [Issues](https://github.com/PIYUSH-MISHRA-00/kryon/issues) — bugs and proposals
- [`SUPPORT.md`](SUPPORT.md) — which is which
- [`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md) — how we behave here

## License

[Apache-2.0](LICENSE).
