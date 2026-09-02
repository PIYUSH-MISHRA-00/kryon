# Platform Support

Only features that are implemented **and** covered by tests are marked supported. Everything
else says what it actually is.

## Feature matrix

| Feature | Linux | macOS | Windows | Android | iOS | Browser |
|---|---|---|---|---|---|---|
| Direct process execution | ✅ | ✅ | ✅ | Planned | ❌ Not possible | ❌ Needs a backend |
| Argument-vector execution | ✅ | ✅ | ✅ | Planned | ❌ | ❌ |
| Shell execution | ✅ `sh -c` | ✅ `sh -c` | ✅ `cmd /c` | Planned | ❌ | ❌ |
| stdout / stderr capture | ✅ | ✅ | ✅ | Planned | ❌ | ❌ |
| Streaming with backpressure | ✅ | ✅ | ✅ | Planned | ❌ | ❌ |
| stdin | ✅ | ✅ | ✅ | Planned | ❌ | ❌ |
| Environment control | ✅ | ✅ | ✅ ¹ | Planned | ❌ | ❌ |
| Working directory | ✅ | ✅ | ✅ | Planned | ❌ | ❌ |
| Timeout | ✅ | ✅ | ✅ | Planned | ❌ | ❌ |
| Output limits | ✅ | ✅ | ✅ | Planned | ❌ | ❌ |
| Cancellation | ✅ | ✅ | ✅ | Planned | ❌ | ❌ |
| `terminate()` | ✅ `SIGTERM` | ✅ `SIGTERM` | ⚠️ No graceful stop | Planned | ❌ | ❌ |
| `kill()` | ✅ `SIGKILL` | ✅ `SIGKILL` | ✅ | Planned | ❌ | ❌ |
| Arbitrary `signal()` | ✅ ¹¹ | ✅ ¹¹ | ❌ No signals exist | Planned | ❌ | ❌ |
| `result.signal` reported | ✅ | ✅ | ❌ Always `None` | Planned | ❌ | ❌ |
| Process-tree termination | ❌ Planned | ❌ Planned | ❌ Planned | ❌ | ❌ | ❌ |
| PTY | ❌ Planned | ❌ Planned | ❌ Planned (ConPTY) | ❌ Planned | ❌ Not possible | ❌ Backend |
| Terminal emulation | ❌ Planned | ❌ Planned | ❌ Planned | ❌ Planned | ❌ Planned | ❌ Planned |
| WebSocket transport | ❌ Planned | ❌ Planned | ❌ Planned | ❌ Planned | ❌ Planned | ❌ Planned |
| SSH adapter | ❌ Planned | ❌ Planned | ❌ Planned | ❌ Planned | ❌ Planned | ❌ Backend |

✅ implemented and tested · ⚠️ works, with a difference you must know about · ❌ not
available, with the reason

¹ `clear_env=True` still preserves `SystemRoot` and `SystemDrive` on Windows. Many binaries,
including ones in `System32`, fail to start without them.

¹¹ On the JVM (Java and Kotlin), `signal()` accepts only `SIGTERM` (15) and `SIGKILL` (9). See
[JVM limitations](#jvm-limitations).

## Windows differences that will bite you

These are real, they are not going away, and Kryon does not paper over them.

### There is no `SIGTERM`

`terminate()` on POSIX asks a process to stop and lets it flush buffers, close files and run
cleanup handlers. `terminate()` on Windows calls `TerminateProcess`, which stops it
immediately with no chance to do any of that.

`terminate()` and `kill()` are therefore the *same operation* on Windows. Cross-platform
cleanup code must be written knowing that its Windows path gets no notice at all.

### `signal()` raises

There are no POSIX signals to send. `Process.signal()` raises `UnsupportedPlatform`, which is
distinct from a transient failure: it means *never here*, so retrying is pointless.

### `result.signal` is always `None`

Windows reports an exit code, not a terminating signal. A process killed with
`TerminateProcess` exits with code `1`, which is indistinguishable from a program that
chose to exit `1`. This is why `termination` exists as a separate field: a Kryon-initiated
stop reports `TIMEOUT` or `CANCELLED` regardless of what the exit code looks like.

### Exit codes are 32-bit

POSIX exit codes are 0–255. Windows exit codes use the full 32-bit range, and are commonly
negative when interpreted as signed (`0xC0000005` for an access violation). Do not assume
`0 <= exit_code <= 255`.

### The command line is a string

Windows has no `execve`. The kernel receives one string and each process parses it back into
arguments itself — usually with the Microsoft C runtime's rules, but not always. Kryon uses
the standard library's quoting because it is far better tested than anything written here
would be, but a program with unusual parsing (notably `cmd.exe`) can still surprise you with
arguments containing `&`, `|`, `^` or `%`.

### `PATH` resolution includes `PATHEXT`

`execute("git")` finds `git.exe`; it can also find `git.cmd` or `git.bat` depending on
`PATHEXT`. A `.bat` file runs through `cmd.exe`, which reintroduces shell parsing for that
process's own arguments. Be aware of what you are actually launching.

## JVM limitations

Two of these are the JDK's, not Kryon's, and both are stated rather than hidden.

### `signal()` supports only `SIGTERM` and `SIGKILL`

The JDK's `Process` API exposes `destroy()` and `destroyForcibly()` and nothing else. Sending
`SIGHUP`, `SIGUSR1` or anything else would require a native call, and Kryon deliberately makes
none: a JNI dependency in a library whose whole selling point is "no dependencies and no surprises"
is a bad trade for a rarely used signal.

The Python and Dart SDKs send arbitrary signals, because their runtimes expose them.

### A signal death is inferred, not reported

The JVM reports a process killed by a signal as exit value `128 + signum` and gives no signal
number of its own. Kryon derives `signal` from that, which means a program that genuinely calls
`exit(143)` is indistinguishable from one killed by `SIGTERM`.

This is why `termination` exists as a separate field: a Kryon-initiated stop reports `TIMEOUT` or
`CANCELLED` regardless of what the exit value looks like, so the ambiguity never affects the
answer that matters.

The Python and Dart SDKs read the real signal from their runtime and do not have this problem.

## Language runtime support

| SDK | Minimum | Tested in CI |
|---|---|---|
| Python | 3.9 | 3.9, 3.10, 3.11, 3.12, 3.13, 3.14 |
| TypeScript | Node 20 | Node 20, 22, 24 |
| Dart | 3.0 | Stable channel |
| Java | 17 | 17, 21 |
| Kotlin | JVM 17 | 17, 21 |

## Mobile

**Android** can execute processes — it is Linux — but with meaningful restrictions: no
access to other applications' files, a limited set of usable binaries, and behaviour that
varies by API level and vendor. It is a real target, planned, and not yet implemented.

**iOS** does not permit an application to spawn arbitrary child processes. This is not a
missing feature; the platform does not allow it, and no library can change that. The correct
architecture for iOS is a [remote transport](../security/remote-execution.md) to a server
that does the executing. This documentation will not list iOS as "planned" for local
execution, because it will never arrive.

## Browsers

A browser cannot execute host operating-system commands. Any product that appears to do so
is talking to a server.

A browser SDK would therefore provide two things — terminal *rendering*, and a *transport
client* — and the execution happens on an authenticated backend. Keeping those concerns
separate is why [layer 3 and layer 5](../architecture/overview.md) are different layers.

Do not deploy the naive version of this. Read [remote execution](../security/remote-execution.md)
first.

## How platform differences are handled in tests

Cases in the [conformance corpus](../../tests/conformance/cases.json) that only make sense on
one platform carry a `platforms` restriction, and a matching case is written for the other
where one exists.

Differences are never handled by loosening an assertion until it passes everywhere. That
hides the difference from exactly the people who need to know about it, and it is how a
matrix like the one at the top of this page becomes fiction.
