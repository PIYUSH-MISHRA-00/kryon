# Changelog

All notable changes to this project are documented here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html). All SDKs share one
version number, so that "Kryon 0.3" means the same set of capabilities in every language.

While `0.x`, minor versions may contain breaking changes. Each one is listed here with a
migration note.

## [Unreleased]

Nothing yet.

## [0.1.0] — 2026-09-02

The first release. Establishes the specification, the conformance mechanism, and the Python
SDK.

Not published to any package registry.

### Added — Specification

- `spec/execution.md` — one-shot execution: options, results, termination reasons, the
  timeout and output-limit sequences, and the rule that argument-vector execution is the
  default with no `shell` flag permitted.
- `spec/process.md` — long-lived processes: streaming with backpressure, input, signals,
  lifecycle, and scoped resource ownership.
- `spec/errors.md` — the error taxonomy and the rule that failing to start is an error while
  failing during a run is a result.
- `spec/terminal.md` — the PTY and terminal-emulator model. Design; not implemented.
- `spec/transport.md` — the transport interface and the security requirements a transport
  server must satisfy. Design; not implemented.
- `spec/conformance.md` — the helper contract and corpus format that make one test suite
  runnable from five languages.

### Added — Conformance

- `tests/conformance/cases.json` — 36 language-neutral cases covering execution, arguments,
  exit codes, streams, environment, working directory, stdin, timeouts, output limits,
  encoding, shell execution, and process lifecycle. Each records what would break in the real
  world if it regressed.
- The Python runner, executing every case through both the synchronous and asynchronous APIs.

### Added — Python SDK (`kryon` 0.1.0)

- `Runtime.execute()` — argument-vector execution with stdout/stderr capture, exit codes and
  timing. No shell is ever invoked implicitly.
- `Runtime.execute_shell()` — shell execution under a separate name, with an injection
  warning. There is deliberately no `shell=True` option.
- `Runtime.spawn()` / `Process` — streaming output as `(Stream, bytes)` pairs through a
  bounded queue, stdin, signals, `terminate`, `kill`, `wait`, `close`, and context-manager
  scope.
- `kryon.aio.AsyncRuntime` / `AsyncProcess` — identical semantics with native `asyncio`
  cancellation. Cancelling a task terminates the child before the `CancelledError` propagates.
- `ExecutionOptions` — `cwd`, `env`, `clear_env`, `stdin`, `timeout`, `max_output_bytes`,
  `encoding`, `check`, `kill_grace`. Runtime-level defaults, overridable per call.
- `ExecutionResult` — with `termination`, `ok`, `check()`, truncation flags, and a `repr`
  short enough to read in a debugger.
- The error taxonomy: `KryonError`, `InvalidArguments`, `CommandNotFound`, `PermissionDenied`,
  `ProcessStartFailed`, `ProcessFailed`, `ProcessTimeout`, `ProcessCancelled`,
  `ResourceLimitExceeded`, `UnsupportedPlatform`. Each also inherits the closest builtin, so
  `except FileNotFoundError` still catches `CommandNotFound`.
- Zero runtime dependencies. Python 3.9–3.14 on Linux, macOS and Windows.
- Full type annotations, `py.typed`, `mypy --strict` clean.

### Added — Documentation

- Getting started, architecture, and platform-support guides.
- Security documentation: threat model, command execution, remote execution, sandboxing.
- Development documentation: setup, repository structure, branching, testing, releases.
- `docs/guides/why-kryon.md`, including the cases where the answer is "use your standard
  library".
- Static website with no build step.

### Added — Project

- Apache-2.0 licence, security policy, contribution guide, code of conduct, governance,
  support and roadmap documents.
- CI for Python across Linux, macOS and Windows on 3.9–3.14, plus package build validation
  and repository checks. Workflows for the unimplemented SDKs report *not implemented* rather
  than passing vacuously.
- Issue and pull-request templates, and Dependabot.

### Known limitations

Stated plainly so nobody discovers them the hard way:

- **PTY is not implemented.** Programs that buffer their output when not attached to a
  terminal will still buffer. Kryon cannot change that from the outside.
- **Terminal emulation is not implemented.** Output is captured verbatim, escape sequences
  included, and nothing interprets them.
- **No remote transports.** Execution is local only.
- **Process trees are not terminated.** Stopping a process does not stop its descendants on
  any platform.
- **Only the Python SDK exists.** The specification has one implementation, which means it
  has not yet been proven to be a specification rather than a description of Python.
- **Kryon is not a sandbox**, and its limits are resource management rather than containment.
  See [the threat model](docs/security/threat-model.md).

[Unreleased]: https://github.com/PIYUSH-MISHRA-00/kryon/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/PIYUSH-MISHRA-00/kryon/releases/tag/v0.1.0
