# Roadmap

Honest and revised as work lands. A phase is marked done when it is implemented **and**
tested, never when it is designed.

**Current: `0.1.0` — Phases 0–2 complete.**

There are no dates. This is maintained by one person, and a date would be a guess presented
as a commitment.

---

## ✅ Phase 0 — Foundation

Repository, governance, security policy, contribution guide, CI, documentation structure,
website.

## ✅ Phase 1 — Python command execution

The first SDK, demonstrating the architecture end to end.

- `execute` — argument-vector execution, stdout/stderr capture, exit codes
- `execute_shell` — separately named, warning-carrying
- `spawn` / `Process` — streaming with backpressure, stdin, signals, lifecycle
- `AsyncRuntime` — identical semantics with native cancellation
- Environment control, working directory, timeouts, output caps
- The error taxonomy
- Zero runtime dependencies
- Linux, macOS, Windows · Python 3.9–3.14

## ✅ Phase 2 — Cross-language specification

- `spec/execution.md`, `spec/process.md`, `spec/errors.md` — normative
- `spec/terminal.md`, `spec/transport.md` — design
- The helper contract, so cases mean the same thing in every language
- `tests/conformance/cases.json` — the shared corpus, each case carrying its `why`
- The Python runner, executing every case through both the sync and async APIs

---

## ⬜ Phase 3 — PTY on POSIX

Next.

- `openpty` allocation on Linux and macOS
- `PtySession` with resize and `SIGWINCH`
- Interactive shells that behave like shells — line buffering, colour, job control
- Conformance cases for PTY behaviour, asserting behaviour rather than exact bytes

Chosen ahead of the second SDK because PTY is the capability that makes Kryon a *terminal*
platform rather than a process library, and because getting the session model right on one
platform before replicating it across five languages is cheaper than the reverse.

## ⬜ Phase 4 — PTY on Windows

- ConPTY, which is not a POSIX PTY with a different name
- Documenting where its output legitimately differs, rather than pretending it does not
- A compatibility matrix that reflects testing rather than intent

## ⬜ Phase 5 — TypeScript SDK

The proof that the specification is a specification and not a description of Python.

- Node execution and process streaming
- Browser build with **no** execution — rendering and transport client only, and no way to
  accidentally import Node APIs into it
- ESM, proper package exports, generated declarations
- The corpus, running against the same `cases.json`

Expect the specification to change here. That is the point of a second implementation.

## ⬜ Phase 6 — Terminal emulator

- ANSI/VT parser, screen buffer, cursor, attributes, scroll regions, alternate screen
- Colour modelled as indexed-16 / indexed-256 / RGB, not flattened early
- Unicode width, combining characters, grapheme clustering
- Keyboard input encoding, which depends on emulator state and therefore belongs here
- Pure and synchronous: bytes in, screen out, no I/O

Also the point at which a shared native core is worth re-evaluating — the logic is large,
intricate, identical everywhere, and has no I/O to integrate with.

## ⬜ Phase 7 — Dart SDK

Core Dart package. A separate Flutter package only when it has a clear purpose.

## ⬜ Phase 8 — Java and Kotlin SDKs

Java with `CompletableFuture` and proper resource management; Kotlin with coroutines, `Flow`
and structured concurrency. Not one wearing the other's API.

## ⬜ Phase 9 — WebSocket transport

Only after the [security requirements](docs/security/remote-execution.md) are settled and
the wire protocol is specified and versioned.

Ships with a reference server that has authentication, authorization, a command allowlist,
resource limits and audit hooks — or it does not ship. The convenient insecure version will
not be published as an example.

## ⬜ Phase 10 — Web terminal

Browser rendering plus the transport client, and documentation that shows the whole
architecture rather than just Kryon's box in it.

## ⬜ Phase 11 — SSH adapter

An adapter over an existing, audited SSH implementation. Kryon will not implement the
protocol, key exchange or host-key verification. Host-key verification on by default, with no
convenience flag to disable it silently.

## ⬜ Phase 12 — Production hardening

- Process-tree termination via process groups and job objects
- Observability hooks — command started, finished, exited, cancelled — off by default and
  never logging command contents
- Benchmarks with published methodology, not published numbers without one
- Security review
- Android support

## ⬜ `1.0`

Reached when:

- the public API has survived real use by people who are not the author;
- PTY works on Linux, macOS and Windows;
- at least three SDKs pass the full conformance corpus;
- the specification has stopped changing shape;
- there is a compatibility promise worth making.

Not on a date. Not because the version number looks better.

---

## Deliberately not planned

**Becoming a sandbox.** Containers and VMs exist and are better at it. See
[sandboxing](docs/security/sandboxing.md).

**A ready-to-run "expose a shell on a port" server.** The version that is easy to deploy is
the version that ends up in incident reports.

**Implementing the SSH protocol.** Adapting an audited implementation, yes. Writing key
exchange, no.

**A plugin system.** There is one extension point — the transport — and it exists because
remote execution genuinely requires it.

**Competing with mature terminal renderers.** The goal is to feed good ones.

**A CLI, for now.** `kryon exec` sounds appealing and solves nothing that a shell does not
already solve. If it ever ships it will be separate from the runtime API.

---

## Influencing this

Open a [discussion](https://github.com/PIYUSH-MISHRA-00/kryon/discussions) or an
[issue](https://github.com/PIYUSH-MISHRA-00/kryon/issues). A second SDK implementation would
reorder this list faster than anything else, because it is what turns the specification from
a document into a guarantee.
