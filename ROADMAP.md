# Roadmap

Honest and revised as work lands. A phase is marked done when it is implemented **and** tested,
never when it is designed.

**Current: `1.0.0` — Phases 0–8 complete.**

There are no dates. This is maintained by one person, and a date would be a guess presented as a
commitment.

---

## ✅ Phase 0 — Foundation

Repository, governance, security policy, contribution guide, CI, documentation structure,
website.

## ✅ Phase 1 — Python command execution

The first SDK, which established the architecture the other four follow.

- `execute` / `execute_shell` — argument-vector execution, shell behind a separate name
- `spawn` / `Process` — streaming with backpressure, stdin, signals, lifecycle
- `AsyncRuntime` — identical semantics with native cancellation
- Environment control, working directory, timeouts, output caps, error taxonomy
- Zero runtime dependencies · Python 3.9–3.14 · Linux, macOS, Windows

## ✅ Phase 2 — Cross-language specification

`execution.md`, `process.md` and `errors.md` as normative documents; `terminal.md` and
`transport.md` as design. The helper contract and the shared corpus, each case carrying its `why`.

## ✅ Phase 3 — TypeScript SDK

Node execution and streaming with `AbortSignal` cancellation, plus a browser entry point that
exports types and errors and **no runtime** — enforced by the package `exports` map rather than by
discipline. ESM, generated declarations, zero runtime dependencies.

## ✅ Phase 4 — Dart SDK

`Stream`-based output with real backpressure via `StreamController` pause/resume. Zero runtime
dependencies. The output enum is named `OutputStream` so the package does not shadow
`dart:async`'s `Stream` for its users.

## ✅ Phase 5 — Java SDK

Builder-configured options, `AutoCloseable` processes, a bounded-queue `Iterable` for output, and
`-Xlint:all -Werror`. Zero runtime dependencies, Java 17+.

## ✅ Phase 6 — Kotlin SDK

Native Kotlin, not a wrapper over the Java SDK: `suspend` functions, `Flow` output over a bounded
channel, and structured cancellation that actually reaches the blocking `Process.waitFor`
underneath. One dependency, `kotlinx-coroutines-core`.

## ✅ Phase 7 — Conformance across all five

The corpus runs in every SDK. Where a language genuinely cannot satisfy a case — the JVM and Dart
cannot set their own process environment — the runner skips it with a reason naming exactly what
is missing, and CI supplies it so the case runs there.

Five independent implementations is what turned the specification from a document into a
guarantee. Three real bugs were found by writing them:

- the Python platform-skip marks were misapplied, so four orphan-process tests silently never ran;
- Dart's `includeParentEnvironment` resurrected variables the caller had removed;
- Kotlin's cancellation could not interrupt a blocking `waitFor`, so cancelling waited out the
  child.

Each was fixed and is now covered.

## ✅ Phase 8 — `1.0.0`

A stable, specified execution API in five languages, with a compatibility promise (below).

---

## ⬜ Phase 9 — PTY on POSIX

Next.

- `openpty` allocation on Linux and macOS
- `PtySession` with resize and `SIGWINCH`
- Interactive shells that behave like shells — line buffering, colour, job control
- Conformance cases asserting behaviour rather than exact bytes

This is the capability that makes Kryon a *terminal* platform rather than a process library, and
the fix for the buffering surprise every streaming user eventually hits.

Expect it to cost each SDK a native dependency of some kind: FFI in Dart, JNI or a native library
on the JVM, and a native module in Node. That is a real change to the "zero dependencies" position
and will be argued for explicitly rather than slipped in.

## ⬜ Phase 10 — PTY on Windows

ConPTY, which is not a POSIX PTY with a different name, and documentation of where its output
legitimately differs rather than pretending it does not.

## ⬜ Phase 11 — Terminal emulator

- ANSI/VT parser, screen buffer, cursor, attributes, scroll regions, alternate screen
- Colour modelled as indexed-16 / indexed-256 / RGB, not flattened early
- Unicode width, combining characters, grapheme clustering
- Keyboard input encoding, which depends on emulator state and therefore belongs here
- Pure and synchronous: bytes in, screen out, no I/O

Also the point at which a shared native core is worth re-evaluating — the logic is large,
intricate, identical everywhere, and has no I/O to integrate with.

## ⬜ Phase 12 — WebSocket transport

Only after the [security requirements](docs/security/remote-execution.md) are settled and the wire
protocol is specified and versioned.

Ships with a reference server that has authentication, authorization, a command allowlist,
resource limits and audit hooks — or it does not ship. The convenient insecure version will not be
published as an example.

## ⬜ Phase 13 — Web terminal

Browser rendering plus the transport client, and documentation that shows the whole architecture
rather than just Kryon's box in it.

## ⬜ Phase 14 — SSH adapter

An adapter over an existing, audited SSH implementation. Kryon will not implement the protocol,
key exchange or host-key verification. Host-key verification on by default, with no convenience
flag to disable it silently.

## ⬜ Phase 15 — Production hardening

- Process-tree termination via process groups and job objects
- Observability hooks — command started, finished, exited, cancelled — off by default and never
  logging command contents
- Benchmarks with published methodology, not published numbers without one
- Android support
- Security review

---

## What `1.0.0` promises

The [stable areas of the specification](spec/README.md#implementation-status) — execution,
process streaming and the error taxonomy — will not change in a way that breaks callers before
`2.0.0`. That covers:

- the two operations and their argument shape;
- every option's name, default and meaning;
- every result field and termination reason;
- which failures raise and which are returned;
- the error taxonomy and its inheritance.

Additive change is fair game in a minor version: new options, new result fields, new SDKs, and
the whole PTY/emulator/transport stack.

## What `1.0.0` does **not** claim

- **Not a sandbox.** It never will be. See [sandboxing](docs/security/sandboxing.md).
- **No PTY.** Programs that buffer when not attached to a terminal still buffer.
- **No terminal emulation.** Escape sequences are captured verbatim and interpreted by nobody.
- **No remote transports.** Execution is local only.
- **No process-tree termination.** Stopping a process does not stop its descendants.
- **Not widely used yet.** The API is stable because it is specified and five implementations
  agree on it, not because thousands of people have hammered it. That evidence comes later, and
  the roadmap will say so when it does.

An earlier version of this document defined `1.0` as requiring PTY. That definition was written
before five SDKs existed, and it turned out to be the wrong line: a stable, agreed execution API
across five languages is a far stronger compatibility claim than one language with a PTY. The
criteria were rewritten rather than quietly ignored.

---

## Deliberately not planned

**Becoming a sandbox.** Containers and VMs exist and are better at it.

**A ready-to-run "expose a shell on a port" server.** The version that is easy to deploy is the
version that ends up in incident reports.

**Implementing the SSH protocol.** Adapting an audited implementation, yes. Writing key exchange,
no.

**A plugin system.** There is one extension point — the transport — and it exists because remote
execution genuinely requires it.

**Competing with mature terminal renderers.** The goal is to feed good ones.

**A CLI, for now.** `kryon exec` sounds appealing and solves nothing a shell does not already
solve. If it ever ships it will be separate from the runtime API.

---

## Influencing this

Open a [discussion](https://github.com/PIYUSH-MISHRA-00/kryon/discussions) or an
[issue](https://github.com/PIYUSH-MISHRA-00/kryon/issues). Real-world use is now the most valuable
input: the specification has five implementations and needs users who will find the places it is
still wrong.
