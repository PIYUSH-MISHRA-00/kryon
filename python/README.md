# Kryon for Python

**Powerful terminal execution, everywhere.**

Run operating-system commands, stream their output, and manage the processes behind them —
with an API designed so the dangerous thing is the one you have to ask for by name.

> **Status: `0.1.0`, alpha.** Command execution and process streaming are implemented and
> covered by the [cross-language conformance corpus](../tests/conformance/cases.json). PTY,
> terminal emulation and remote transports are specified but **not implemented**. The API
> may change before `1.0`.

This is the Python SDK of [Kryon](https://github.com/PIYUSH-MISHRA-00/kryon). It has **zero
runtime dependencies**.

## Install

```bash
pip install kryon
```

Requires Python 3.9 or newer. Works on Linux, macOS and Windows.

> Not yet published to PyPI. Until the first release, install from source:
> `pip install "kryon @ git+https://github.com/PIYUSH-MISHRA-00/kryon.git#subdirectory=python"`

## Run something

```python
from kryon import Runtime

runtime = Runtime(encoding="utf-8", timeout=30)

result = runtime.execute("git", ["status", "--porcelain"])

print(result.stdout)
print(result.exit_code, result.ok, result.duration)
```

`execute` runs the program to completion and gives you everything it produced.

## Talk to something

```python
with runtime.spawn("python", ["-u", "-i"]) as proc:
    proc.write("print(2 ** 10)\n")
    for stream, chunk in proc:
        print(stream.value, chunk.decode())
```

`spawn` returns as soon as the process starts. Output arrives as it is produced, and the
`with` block guarantees the process cannot outlive it.

## Async

Identical semantics, checked against the same conformance corpus:

```python
import asyncio
from kryon.aio import AsyncRuntime


async def main():
    runtime = AsyncRuntime(encoding="utf-8")
    result = await runtime.execute("git", ["log", "--oneline", "-5"])
    print(result.stdout)


asyncio.run(main())
```

Cancelling the task terminates the child process before the `CancelledError` propagates.
Kryon never hands control back while leaving a process running.

## Two things worth knowing

### Arguments are never interpreted

```python
runtime.execute("echo", ["$HOME && rm -rf /"])  # prints that text, literally
```

No shell is involved, so nothing in an argument can expand, glob, chain or substitute. If
you want shell semantics you have to ask for them by name:

```python
runtime.execute_shell("ls *.py | wc -l")  # /bin/sh -c on POSIX, %COMSPEC% /c on Windows
```

That is a separate method, not a `shell=True` flag, because a boolean among a dozen options
is easy to set by accident and easy to miss in review. **Never build that string from
untrusted input.**

### Kryon is not a sandbox

Timeouts and output caps manage resources. They do not contain a hostile program: a process
that ignores `SIGTERM` still runs until the kill lands, and anything it did before that is
done. Isolation is the job of a container, a VM, or an unprivileged account. See
[the threat model](../docs/security/threat-model.md).

## API

### `Runtime(**defaults)`

Holds default options; safe to share across threads. Every default can be overridden per
call. `env` merges with the runtime's `env`; everything else is replaced.

| Option | Default | Meaning |
|---|---|---|
| `cwd` | inherited | Working directory. A path that is not a directory is an error, never a silent fallback. |
| `env` | `{}` | Variables merged over the inherited environment. `None` removes one. |
| `clear_env` | `False` | Start from an empty environment. With `env`, this is an allowlist. |
| `stdin` | `None` | Data written to stdin, after which stdin is closed. |
| `timeout` | `None` | Seconds. On expiry: terminate, wait `kill_grace`, kill. |
| `max_output_bytes` | `None` | Per-stream cap. Exceeding it stops the process. |
| `encoding` | `None` | Set it for `str` output, leave it for `bytes`. |
| `check` | `False` | Raise on an unsuccessful result. |
| `kill_grace` | `5.0` | Seconds between the polite stop and the forced kill. |

### `ExecutionResult`

`executable`, `arguments`, `exit_code`, `signal`, `stdout`, `stderr`, `duration`,
`termination`, `pid`, `stdout_truncated`, `stderr_truncated`, plus `ok` and `check()`.

`termination` is one of `EXITED`, `SIGNALED`, `TIMEOUT`, `CANCELLED`, `OUTPUT_LIMIT`. The
Kryon-initiated reasons win over the kernel's account: a process killed for exceeding its
timeout reports `TIMEOUT`, because that is what the caller needs in order to react.

### `Process`

`pid`, `running`, `exit_code`, `write()`, `close_stdin()`, `output`, `signal()`,
`terminate()`, `kill()`, `wait()`, `close()`, and the context-manager protocol.

`output` yields `(Stream, bytes)` pairs through a bounded queue. Stop consuming and Kryon
stops reading, so the child blocks instead of your heap growing.

### Errors

The rule: **failing to start is an error, failing while running is a result.**

`CommandNotFound`, `PermissionDenied`, `ProcessStartFailed` and `InvalidArguments` are raised
— no process ran. `ProcessFailed`, `ProcessTimeout`, `ProcessCancelled` and
`ResourceLimitExceeded` are raised only under `check=True`, and each carries the
`ExecutionResult` it came from. All descend from `KryonError`, and each also inherits the
closest builtin, so `except FileNotFoundError` still catches `CommandNotFound`.

`UnsupportedPlatform` means *never here* — `Process.signal()` on Windows, which has no
signals to send.

## Platform notes

| | Linux | macOS | Windows |
|---|---|---|---|
| `execute` / `spawn` | Yes | Yes | Yes |
| `signal()` | Yes | Yes | `UnsupportedPlatform` |
| `terminate()` | `SIGTERM` | `SIGTERM` | `TerminateProcess` — no graceful stop |
| `result.signal` | Reported | Reported | Always `None` |

Windows has no `SIGTERM`. `terminate()` there is the same operation as `kill()`, and the
child gets no chance to flush. Kryon does not paper over that.

## Develop

```bash
cd python
pip install -e ".[dev]"
pytest                 # unit tests + the shared conformance corpus, sync and async
ruff check . && ruff format --check .
mypy
```

The conformance corpus is [`tests/conformance/cases.json`](../tests/conformance/cases.json)
in the repository root and is shared with every other SDK. Tests drive a small
[helper program](tests/helper.py) rather than real system commands, so they behave the same
on every platform and touch nothing outside a temporary directory.

## License

Apache-2.0. See [LICENSE](LICENSE).
