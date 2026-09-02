# Process Specification

**Status:** `1.0` — implemented and verified by five SDKs

Defines long-lived processes: streaming output, writing input, sending signals, and
lifecycle management. Where [`execution.md`](execution.md) covers "run this and tell me what
happened", this document covers "start this and let me talk to it".

## 1. Why this is a separate API

A blocking `execute` cannot express a process you interact with while it runs. Forcing
interactive work through callbacks bolted onto a one-shot call produces an API where the
lifecycle is implicit and resource ownership is unclear. Kryon therefore exposes a second,
explicitly stateful operation:

```
runtime.spawn(executable, arguments, options) -> Process
```

`spawn` returns as soon as the process has started. Everything after that is driven by the
caller.

## 2. `Process`

### 2.1 Identity and state

| Member | Meaning |
|---|---|
| `pid` | Operating-system process id. |
| `running` | Whether the process is still alive. |
| `exit_code` | Exit status once the process has been reaped; absent while running. |

### 2.2 Input

| Operation | Behaviour |
|---|---|
| `write(data)` | Write bytes or text to the child's stdin. |
| `close_stdin()` | Close stdin, signalling end-of-input to the child. |

`write` **MUST** be safe to call while output is being consumed. Writing to a process whose
stdin has been closed, or which has exited, **MUST** raise rather than fail silently —
silently dropping input is the failure mode that produces unreproducible hangs.

### 2.3 Output

Output is exposed as a stream of chunks, using the host language's natural streaming
primitive: an iterator or generator in Python, an async iterable or `Readable` in
JavaScript, a `Stream` in Dart, a `Flow` in Kotlin, a reactive or blocking stream in Java.

| Operation | Behaviour |
|---|---|
| `stdout` | Chunks from standard output. |
| `stderr` | Chunks from standard error. |
| `output` | Chunks from both, tagged with their origin, in arrival order. |

Chunk boundaries are **not** meaningful. They reflect how the operating system delivered the
data, not line or record structure. Implementations **MUST NOT** promise line-aligned chunks
from the raw stream; a separate line-oriented helper MAY be offered, and if offered **MUST**
document its buffering behaviour.

Streams **MUST** apply backpressure: a slow consumer **MUST** cause Kryon to stop reading
from the pipe rather than accumulate unbounded memory. Where the host language's stream type
has no backpressure mechanism, the implementation **MUST** apply a bounded internal buffer
and document its size.

### 2.4 Lifecycle

| Operation | Behaviour |
|---|---|
| `signal(sig)` | Send a specific signal. POSIX only; **MUST** raise `UnsupportedPlatform` on Windows. |
| `terminate()` | Polite stop: `SIGTERM` on POSIX, `TerminateProcess` on Windows. |
| `kill()` | Forced stop: `SIGKILL` on POSIX, `TerminateProcess` on Windows. |
| `wait(timeout)` | Block until exit, or until the timeout elapses. |
| `close()` | Release all resources; terminate the process if still running. |

On Windows, `terminate()` and `kill()` are the same operation. SDKs **MUST NOT** hide this;
callers writing cross-platform cleanup need to know their child gets no chance to flush.

### 2.5 Resource ownership

`Process` **MUST** be usable with the host language's scoped-resource construct — a `with`
statement, `try`-with-resources, `use`, `await using`. Leaving the scope **MUST** terminate
the process if it is still running and **MUST** close every pipe.

An abandoned `Process` that is garbage-collected while running **SHOULD** produce a warning
where the language permits one. Leaking a child process is a bug the developer needs told
about, not one to swallow.

## 3. Termination sequence

`close()`, scope exit and cancellation all use the same sequence as
[`execution.md` §5](execution.md#5-timeouts): polite stop, wait up to `kill_grace`, forced
kill. This is the single termination path in Kryon; implementations **MUST NOT** invent a
second one.

## 4. Process trees

Terminating a process does **not** terminate its descendants on any supported platform
without additional work. Where a platform offers a mechanism — process groups on POSIX, job
objects on Windows — SDKs MAY expose it, and **MUST** document exactly what is and is not
killed.

Until then, documentation **MUST NOT** imply that stopping a shell stops what the shell
started.

## 5. Concurrency

A single `Process` **MUST** tolerate one reader per stream plus one writer concurrently.
Two concurrent readers of the same stream is a caller error and **MAY** raise.

## 6. Relationship to `execute`

`execute` is specified as being expressible in terms of `spawn`: spawn, write `stdin`, drain
both streams under the output cap, wait under the timeout, build the result. Implementations
are not required to literally share the code — a buffered path can be meaningfully simpler
and faster — but **MUST NOT** produce different observable behaviour for the same options.
The conformance corpus tests both paths against the same expectations.
