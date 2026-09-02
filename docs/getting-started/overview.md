# Overview

## What Kryon is

Kryon is a terminal execution platform: a way to run operating-system commands, drive
long-lived and interactive processes, stream their output, and — as the project matures —
allocate real pseudo-terminals, emulate terminal state, and carry all of that over a
transport to somewhere else.

The part that exists today is the foundation: **command execution and process control**,
implemented in Python, specified language-neutrally, and verified by a conformance corpus
that every future SDK must pass.

## What Kryon is not

**Not a sandbox.** Kryon runs whatever you tell it to run, with the privileges your process
already has. Its timeouts and output caps are resource management, not containment. See the
[threat model](../security/threat-model.md).

**Not a terminal emulator you drop into a page.** Rendering is a separate concern, and
excellent components already exist for it. Kryon's interest is what sits behind the
rendering.

**Not production-ready.** It is `0.1.0`. The API will change.

## Why it exists

Running a command is easy in every language. Doing it *well* is not, and every ecosystem
re-solves the same problems badly:

- **Shell by default.** The convenient API takes a string and hands it to a shell, so
  interpolating a filename becomes a command-injection bug. The safe API is longer to type,
  so it loses.
- **Timeouts that leak.** The call returns after the timeout; the process keeps running.
- **Unbounded output.** A command that prints a gigabyte takes the process down with it,
  because the buffer grew until it could not.
- **Errors that discard evidence.** The exception says "exit code 1" and throws away the
  4 KB of stderr explaining why.
- **Interactive processes bolted onto a one-shot API.** Callbacks on a blocking call, with
  no clear owner of the process's lifetime.
- **Five languages, five different answers.** A team that runs commands from a Python
  service, a Node CLI and a Kotlin backend gets three different behaviours for the same
  timeout.

Kryon's proposition is not "a better `subprocess`". It is *one* well-specified execution
model, implemented natively and identically across languages, with the terminal layers
built on the same foundation instead of alongside it.

## The shape of the API

Two operations, deliberately separate:

```python
# Run it and tell me what happened.
result = runtime.execute("git", ["status", "--porcelain"])

# Start it and let me talk to it.
with runtime.spawn("python", ["-u", "-i"]) as proc:
    proc.write("print(2 ** 10)\n")
    for stream, chunk in proc:
        ...
```

They are separate because a blocking call cannot express a conversation, and forcing
interactive work through callbacks on a one-shot function produces an API where nobody owns
the process. See [`spec/process.md`](../../spec/process.md).

## Three decisions worth knowing up front

### Arguments are never interpreted

`execute("echo", ["$HOME && rm -rf /"])` prints that text. No shell sees it. Shell semantics
require [`execute_shell`](../security/command-execution.md) — a separate method name, not a
`shell=True` flag, because a boolean among a dozen options is easy to set by accident and
easy to miss in review.

### Failing to start is an error; failing while running is a result

A missing executable raises: nothing ran, so there is no result to give you. A process that
exited `1` returns a result, because `grep` exits `1` to mean "no match" and treating that
as an exception makes ordinary code wrong by default. `check=True` opts into the strict
style.

### The reason Kryon intervened wins

A process killed for exceeding its timeout was, at the kernel level, killed by a signal.
Kryon reports `TIMEOUT`, because that is the fact you need in order to decide whether to
retry.

## Where to go next

- [Install it](installation.md)
- [Run something](first-terminal.md)
- [Read the threat model](../security/threat-model.md) before it touches anything untrusted
- [See what is actually implemented](../../spec/README.md#implementation-status)
