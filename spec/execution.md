# Execution Specification

**Status:** `1.0` — implemented and verified by five SDKs

Defines one-shot command execution: run a program to completion, collect its output, get a
result. This is the smallest useful unit of Kryon and the first thing every SDK implements.

## 1. Conceptual API

```
runtime.execute(executable, arguments, options) -> ExecutionResult
```

- `executable` — the program to run. A bare name is resolved through `PATH`; a path
  containing a separator is used as-is.
- `arguments` — an ordered list of arguments, passed to the operating system as a vector.
  Implementations **MUST NOT** join arguments into a string and re-split them.
- `options` — see §3.

`execute` runs the process to completion (or termination) and buffers its output. For
processes that stream, are interactive, or outlive the call, see [`process.md`](process.md).

## 2. Argument-vector execution is the default

Kryon **MUST NOT** invoke a shell implicitly. `execute("echo", ["$HOME; rm -rf /"])` passes
one literal argument to `echo`; no variable expansion, no word splitting, no command
chaining occurs.

Shell semantics are available only through a **separately named** operation:

```
runtime.execute_shell(command_line, options) -> ExecutionResult
```

This is a deliberate API design decision, not a stylistic one. A boolean `shell=true` flag
sitting among a dozen other options is easy to set by accident and easy to miss in review;
a differently named method is not. Implementations **MUST NOT** offer a boolean flag that
switches `execute` into shell mode.

`execute_shell` **MUST** document the shell it invokes (`/bin/sh -c` on POSIX,
`cmd.exe /d /s /c` on Windows) and **MUST** carry an injection warning in its API docs.

## 3. `ExecutionOptions`

Every field is optional. Names are given in conceptual form; SDKs use their own casing.

| Field | Type | Default | Meaning |
|---|---|---|---|
| `cwd` | path | inherited | Working directory for the child process. |
| `env` | map | `{}` | Variables merged **over** the inherited environment. |
| `clear_env` | boolean | `false` | Start from an empty environment instead of inheriting. Combined with `env`, this is an allowlist. |
| `stdin` | bytes / text / none | none | Data written to the child's stdin, after which stdin is closed. |
| `timeout` | duration | none | Wall-clock limit. See §5. |
| `max_output_bytes` | integer | none | Per-stream output cap. See §6. |
| `encoding` | string / none | none | When set, `stdout`/`stderr` are decoded text; when unset, they are bytes. |
| `check` | boolean | `false` | Raise instead of returning on a non-successful result. See §7. |
| `kill_grace` | duration | `5s` | Time between the polite stop signal and the forced kill. |

### 3.1 Environment semantics

`env` merges over the parent environment; it does not replace it. To run a process with a
strictly controlled environment, set `clear_env` and list exactly what is permitted:

```
options(clear_env=true, env={"PATH": "/usr/bin", "LANG": "C.UTF-8"})
```

Implementations **MUST NOT** silently inject variables the caller did not ask for, with one
exception that the platform requires: on Windows, `SystemRoot` and `SystemDrive` MAY be
preserved when `clear_env` is set, because many binaries fail to start without them. Any
such exception **MUST** be documented by the SDK.

A `null` value in `env` **MUST** remove that variable from the child environment.

### 3.2 Working directory

If `cwd` does not exist or is not a directory, the operation **MUST** fail with
`ProcessStartFailed` (or `PermissionDenied` where that is the actual cause) and **MUST NOT**
silently fall back to the current directory.

## 4. `ExecutionResult`

| Field | Meaning |
|---|---|
| `executable` | The executable as requested. |
| `arguments` | The argument vector as requested. |
| `exit_code` | Integer exit status, or absent if the process did not exit normally. |
| `signal` | The terminating signal, where the platform reports one. Absent on Windows. |
| `stdout` | Captured standard output (bytes, or text when `encoding` was set). |
| `stderr` | Captured standard error. |
| `duration` | Wall-clock time from spawn to reap. |
| `termination` | Why the process stopped. See §4.1. |
| `pid` | The operating-system process id. |
| `stdout_truncated` / `stderr_truncated` | Whether the output cap discarded data. |

Derived: `ok` is `true` when `termination` is `EXITED` **and** `exit_code == 0`.

### 4.1 `TerminationReason`

| Value | Meaning |
|---|---|
| `EXITED` | The process exited on its own. `exit_code` is present. |
| `SIGNALED` | The process was terminated by a signal it did not choose. |
| `TIMEOUT` | Kryon stopped the process because `timeout` elapsed. |
| `CANCELLED` | Kryon stopped the process because the caller cancelled the operation. |
| `OUTPUT_LIMIT` | Kryon stopped the process because `max_output_bytes` was exceeded. |

`TIMEOUT`, `CANCELLED` and `OUTPUT_LIMIT` describe *why Kryon intervened*; the process was
still terminated by a signal or a kill underneath, and `signal` MAY additionally be set.
The Kryon-level reason always takes precedence in the `termination` field, because that is
the fact the caller needs in order to react correctly.

## 5. Timeouts

When `timeout` elapses:

1. Kryon sends the platform's polite termination request (`SIGTERM` on POSIX; on Windows,
   the platform's process-termination call, as Windows has no `SIGTERM` equivalent).
2. It waits up to `kill_grace`.
3. If the process is still alive, it forces termination (`SIGKILL` on POSIX).
4. Output collected before termination is returned; it is not discarded.

A timeout **MUST** produce `termination = TIMEOUT`, and **MUST** raise `ProcessTimeout` when
`check` is set. Implementations **MUST NOT** leave an orphaned child behind after a timeout.

Timeouts are a liveness mechanism, not a security boundary: a process that ignores
termination signals and holds resources can still do damage before it is killed.

## 6. Output limits

`max_output_bytes` applies **per stream**, counted in bytes before any decoding. When a
stream exceeds the cap, Kryon stops the process using the sequence in §5, sets the matching
`*_truncated` flag, and reports `termination = OUTPUT_LIMIT`.

The purpose is bounded memory. Implementations **MUST NOT** buffer beyond the cap in order
to report an accurate total size — the whole point is not to hold the data.

## 7. Failure model

Kryon draws a hard line between *starting* and *running*:

- **Failing to start is an error.** `CommandNotFound`, `PermissionDenied` and
  `ProcessStartFailed` are raised (or their language's equivalent). No result exists,
  because no process ran.
- **Failing while running is a result.** A non-zero exit, a timeout, a signal, a hit output
  cap — these are outcomes of a process that really ran, and are reported in
  `ExecutionResult`.

`check = true` converts the second category into raised errors as well, for callers who want
the strict style. It never converts the first category into a result.

See [`errors.md`](errors.md) for the full taxonomy.

## 8. Cancellation

Where the host language has a native cancellation mechanism — cancelling an async task, a
cancellation token, a structured-concurrency scope — Kryon **MUST** honour it: cancelling an
in-flight `execute` terminates the child process using the §5 sequence. Kryon **MUST NOT**
return control to the caller while leaving the process running.

## 9. Concurrency and reentrancy

A `Runtime` **MUST** be safe to use from multiple threads or tasks concurrently. It holds
configuration, not per-call state. Implementations **MUST NOT** use global mutable state to
track running processes.

## 10. Platform behaviour

| Behaviour | Linux | macOS | Windows |
|---|---|---|---|
| Argument-vector execution | Native | Native | Emulated via the C runtime's quoting rules |
| `PATH` resolution | Native | Native | Includes `PATHEXT` expansion |
| Polite termination | `SIGTERM` | `SIGTERM` | `TerminateProcess` — no graceful signal exists |
| `signal` in result | Reported | Reported | Absent |
| Exit codes | 0–255 | 0–255 | Full 32-bit range |

Windows has no equivalent of `SIGTERM`. A "polite" stop on Windows terminates the process
without giving it a chance to clean up. SDKs **MUST NOT** paper over this difference and
**MUST** document it wherever termination is described.
