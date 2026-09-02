# Changelog

All notable changes to this project are documented here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html). All SDKs share one version
number, so that "Kryon 1.0" means the same set of capabilities in every language.

## [Unreleased]

Nothing yet.

## [1.0.0] — 2026-09-02

**All five SDKs, one specification, one shared test corpus.**

The execution API is now stable. Four new SDKs join Python, and all five pass the same
conformance corpus on Linux, macOS and Windows.

### Added — TypeScript SDK (`kryon` on npm)

- `Runtime.execute` / `executeShell` / `spawn`, with `AbortSignal` cancellation.
- `KryonProcess` — output as an async iterable over a bounded queue with real backpressure
  (the source streams are paused, not buffered), `Symbol.asyncDispose` for `await using`.
- A browser entry point (`kryon/browser`) that exports the value types and error taxonomy and
  **no runtime**, enforced by the package `exports` map so a bundler cannot pull
  `node:child_process` into a web bundle by accident.
- ESM, generated declarations, zero runtime dependencies, Node 20+.
- Tests use Node's built-in runner — no test framework dependency.

### Added — Dart SDK (`kryon` on pub.dev)

- `Runtime.execute` / `executeShell` / `spawn`, `KryonProcess` with a single-subscription
  `Stream<OutputChunk>`.
- Backpressure through `StreamController` pause/resume, so a paused collector pauses the pipe.
- Zero runtime dependencies, Dart 3.0+.
- The output enum is named `OutputStream`: a package exporting a type called `Stream` would
  shadow `dart:async`'s for every user who imported it.

### Added — Java SDK (`io.github.piyush-mishra-00:kryon`)

- Builder-configured `ExecutionOptions`, `AutoCloseable` `KryonProcess`, output as a one-shot
  `Iterable<OutputChunk>` drained from a bounded queue.
- Zero runtime dependencies, Java 17+, compiled with `-Xlint:all -Werror`.
- Two JVM limitations documented rather than hidden: `signal(int)` supports only `SIGTERM` and
  `SIGKILL` because the JDK exposes nothing else, and a signal death is inferred from
  `128 + signum` because the JDK reports no signal number.

### Added — Kotlin SDK (`io.github.piyush-mishra-00:kryon-kotlin`)

- A native Kotlin implementation, not a wrapper over the Java SDK: `suspend` functions, `Flow`
  output over a bounded channel, structured cancellation.
- Cancellation reaches the blocking `Process.waitFor` via `runInterruptible`, so cancelling
  actually stops the child rather than waiting it out.
- `explicitApi()` and `allWarningsAsErrors` both on.
- One dependency, `kotlinx-coroutines-core`.

### Changed

- **Python `0.1.0` → `1.0.0`.** No API changes. The version reflects that the specification is
  now stable and agreed by five implementations.
- The specification's execution, process and error documents are marked `1.0` and covered by the
  compatibility promise in [`ROADMAP.md`](ROADMAP.md). PTY, terminal emulation and transports
  remain design documents.
- `spec/conformance.md` gained §3.1 (durations in the corpus are seconds; each runner converts to
  its own idiom) and §3.2 (`setup_env` requires the variable in the runner's own environment;
  runners in languages that cannot set it must skip with a reason rather than pass it through
  `env`, which would silently turn a test about inheritance into a test about merging).
- The `1.0` criteria in `ROADMAP.md` were rewritten. The earlier definition required PTY; five
  agreeing implementations of a specified execution API turned out to be the stronger
  compatibility claim. The criteria were changed openly rather than quietly ignored.

### Fixed

Three real bugs, each found by writing a second implementation or by the cross-platform matrix:

- **Python:** the platform-skip marks were applied as `@posix_only("reason")`, which pytest reads
  as a condition *string* to evaluate. Every POSIX-only test errored instead of running — and it
  passed on Windows because those tests skip before evaluation. The four affected tests were the
  ones asserting that no orphaned process is left behind.
- **Dart:** `Process.start` was called with both an explicit environment map and
  `includeParentEnvironment: true`, so variables the caller removed via `env: {NAME: null}` were
  silently restored by `dart:io`.
- **Kotlin:** cancelling a coroutine does not interrupt a blocking call, so cancelling an
  `execute` sat in `Process.waitFor` until the child finished on its own — exactly the "returns
  control while leaving a process running" failure the design exists to prevent.
- **TypeScript:** an `AbortSignal` that was *already* aborted never fires its event, so the
  listener alone missed it and the process ran to completion.
- **Website:** the header overflowed the viewport on 320–360px devices, forcing a horizontal
  scrollbar on the whole page.

### Known limitations

Stated plainly so nobody discovers them the hard way:

- **PTY is not implemented.** Programs that buffer their output when not attached to a terminal
  will still buffer. Kryon cannot change that from the outside.
- **Terminal emulation is not implemented.** Output is captured verbatim, escape sequences
  included, and nothing interprets them.
- **No remote transports.** Execution is local only.
- **Process trees are not terminated.** Stopping a process does not stop its descendants on any
  platform.
- **Kryon is not a sandbox**, and its limits are resource management rather than containment. See
  [the threat model](docs/security/threat-model.md).

## [0.1.0] — 2026-09-02

The first release. Established the specification, the conformance mechanism, and the Python SDK.

### Added

- `spec/` — `execution.md`, `process.md`, `errors.md` as normative documents; `terminal.md` and
  `transport.md` as design; `conformance.md` defining the helper contract.
- `tests/conformance/cases.json` — 36 language-neutral cases, each recording what would break in
  the real world if it regressed.
- Python SDK: `Runtime.execute` / `execute_shell` / `spawn`, `kryon.aio.AsyncRuntime`, the full
  option set, `ExecutionResult`, the error taxonomy, zero runtime dependencies, Python 3.9–3.14.
- Documentation: getting started, architecture, security (threat model, command execution, remote
  execution, sandboxing), development, platform support.
- Static website with no build step, CI across three platforms, issue and PR templates,
  Dependabot, governance, security policy and code of conduct.

Published to PyPI as `kryon` 0.1.0.

[Unreleased]: https://github.com/PIYUSH-MISHRA-00/kryon/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/PIYUSH-MISHRA-00/kryon/compare/v0.1.0...v1.0.0
[0.1.0]: https://github.com/PIYUSH-MISHRA-00/kryon/releases/tag/v0.1.0
