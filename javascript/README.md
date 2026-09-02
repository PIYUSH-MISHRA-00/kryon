# Kryon for TypeScript

**Powerful terminal execution, everywhere.**

Run operating-system commands, stream their output, and manage the processes behind them —
with an API designed so the dangerous thing is the one you have to ask for by name.

[![npm](https://img.shields.io/npm/v/kryon-exec.svg)](https://www.npmjs.com/package/kryon-exec)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Node](https://img.shields.io/badge/node-%E2%89%A520-339933.svg)](package.json)

This is the TypeScript/JavaScript SDK of [Kryon](https://github.com/PIYUSH-MISHRA-00/kryon).
**Zero runtime dependencies.** Written in TypeScript, shipped with declarations, ESM-only.

> **`1.0.0`.** Command execution and process streaming are implemented and pass the
> [cross-language conformance corpus](../tests/conformance/cases.json) on Linux, macOS and
> Windows. PTY, terminal emulation and remote transports are specified but **not implemented**.

## Install

```bash
npm install kryon-exec
```

> **On the name.** The npm registry refuses `kryon` as too similar to the existing
> `cron` package -- a typosquatting filter rather than a name clash. The package is
> `kryon-exec` here; it is `kryon` on PyPI and pub.dev. Same project, same version, same
> conformance corpus.

Requires Node 20 or newer.

## Run something

```ts
import { Runtime } from "kryon-exec";

const runtime = new Runtime({ encoding: "utf8", timeout: 30_000 });

const result = await runtime.execute("git", ["status", "--porcelain"]);

console.log(result.stdout);
console.log(result.exitCode, result.termination, result.duration);
```

## Talk to something

```ts
import { Runtime, Stream } from "kryon-exec";

const proc = await runtime.spawn("npm", ["install"]);
try {
  for await (const { stream, data } of proc.output) {
    process[stream === Stream.STDERR ? "stderr" : "stdout"].write(data);
  }
  const result = await proc.wait();
  console.log("exit", result.exitCode);
} finally {
  await proc.close();
}
```

Or let the runtime own the lifetime:

```ts
await using proc = await runtime.spawn("npm", ["install"]);
for await (const chunk of proc.output) process.stdout.write(chunk.data);
```

`await using` requires TypeScript 5.2+ and Node 20+; the `try`/`finally` above is the
equivalent everywhere else.

## Two things worth knowing

### Arguments are never interpreted

```ts
await runtime.execute("wc", ["-l", userInput]);        // safe, whatever userInput is
await runtime.executeShell(`wc -l ${userInput}`);      // command injection
```

`execute` passes an argument vector to the operating system. No shell is involved, so nothing
in an argument can expand, glob, chain or substitute. Shell semantics live behind
`executeShell` — a **separate method name**, not a `shell: true` flag, because a boolean among a
dozen options is easy to set by accident and easy to miss in review.

### Kryon is not a sandbox

Timeouts and output caps manage resources. They do not contain a hostile program: a process
that ignores `SIGTERM` runs until the kill lands, and anything it did before that is done.
Isolation is a container, a VM, or an unprivileged account. See
[the threat model](../docs/security/threat-model.md).

## API

### `new Runtime(defaults?)`

Holds default options; safe to share. Every default can be overridden per call. `env` merges
with the runtime's `env`; everything else is replaced.

| Option | Default | Meaning |
|---|---|---|
| `cwd` | inherited | Working directory. A path that is not a directory is an error, never a silent fallback. |
| `env` | `{}` | Variables merged over the inherited environment. `null` removes one. |
| `clearEnv` | `false` | Start from an empty environment. With `env`, this is an allowlist. |
| `stdin` | — | Data written to stdin, after which stdin is closed. |
| `timeout` | — | **Milliseconds.** On expiry: terminate, wait `killGrace`, kill. |
| `maxOutputBytes` | — | Per-stream cap, enforced during the flood. |
| `encoding` | — | Set it for `string` output, leave it for `Buffer`. |
| `check` | `false` | Throw on an unsuccessful result. |
| `killGrace` | `5000` | Milliseconds between the polite stop and the forced kill. |
| `signal` | — | An `AbortSignal`. Aborting terminates the child before the promise rejects. |

Durations are milliseconds here because that is this ecosystem's idiom; the Python SDK uses
seconds and the JVM SDKs use `Duration`. The semantics are identical — only the spelling differs.

### `ExecutionResult`

`executable`, `args`, `exitCode`, `signal`, `stdout`, `stderr`, `duration`, `termination`,
`pid`, `stdoutTruncated`, `stderrTruncated`. Plus the free functions `isOk(result)` and
`check(result)`.

`termination` is `EXITED`, `SIGNALED`, `TIMEOUT`, `CANCELLED` or `OUTPUT_LIMIT`. The
Kryon-initiated reasons win over the kernel's account: a process killed for exceeding its
timeout reports `TIMEOUT`, because that is what you need in order to decide whether to retry.

### `KryonProcess`

`pid`, `running`, `exitCode`, `write()`, `closeStdin()`, `output`, `signal()`, `terminate()`,
`kill()`, `wait()`, `close()`, and `Symbol.asyncDispose`.

`output` yields `{ stream, data }` through a bounded queue. Stop consuming and Kryon pauses the
source streams, so the child blocks instead of your heap growing.

### Errors

The rule: **failing to start is an error, failing while running is a result.**

`CommandNotFoundError`, `PermissionDeniedError`, `ProcessStartFailedError` and
`InvalidArgumentsError` reject — no process ran. `ProcessFailedError`, `ProcessTimeoutError` and
`ResourceLimitExceededError` reject only under `check: true`, and each carries the
`ExecutionResult` it came from. `ProcessCancelledError` always rejects, because a cancellation is
the caller's own doing. All descend from `KryonError`.

## Browsers

```ts
import { TerminationReason, isOk } from "kryon-exec/browser";
```

A browser cannot execute host operating-system commands — that is what a browser is, not a gap
in Kryon. The browser entry point exports the value types and the error taxonomy and **no
runtime**; the `exports` map enforces it, so a bundler cannot pull `node:child_process` into a
web bundle by accident.

Building a browser terminal means executing on an authenticated backend and carrying the session
over a transport. Read [remote execution](../docs/security/remote-execution.md) first — the
version that is easy to deploy is the version that ends up in an incident report.

## Platform notes

| | Linux | macOS | Windows |
|---|---|---|---|
| `execute` / `spawn` | Yes | Yes | Yes |
| `signal()` | Yes | Yes | `UnsupportedPlatformError` |
| `terminate()` | `SIGTERM` | `SIGTERM` | `TerminateProcess` — no graceful stop |
| `result.signal` | Reported | Reported | Always `null` |

Windows has no `SIGTERM`. `terminate()` there is the same operation as `kill()`, and the child
gets no chance to flush. Kryon does not paper over that.

## Develop

```bash
cd javascript
npm install
npm run build
npm test          # unit tests + the shared conformance corpus
npm run typecheck
```

Tests use Node's built-in runner — no test framework dependency. They drive a small
[helper program](test/helper.mjs) rather than real system commands, so they behave the same on
every platform and touch nothing outside a temporary directory.

## License

Apache-2.0. See [LICENSE](LICENSE).

---

If Kryon saves you time, you can
[buy me a coffee](https://buymeacoffee.com/piyushmishra00).
