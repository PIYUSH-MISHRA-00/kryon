# Threat Model

Kryon executes arbitrary system commands. This document states plainly what it defends
against and what it does not, so that nobody deploys it believing in a boundary that is not
there.

**Read the last section first if you are short of time.**

## The one-line version

> Kryon is a well-behaved way to run programs you already have the right to run. It is not
> a way to safely run programs you do not trust.

## Trust boundaries

Kryon sits inside your application's trust boundary. It does not create one.

```
┌─────────────────────────────────────────────────────────┐
│ your application's privileges                           │
│                                                         │
│   your code ──▶ Kryon ──▶ child process                 │
│                                                         │
│   The child inherits: your user, your file access,      │
│   your network access, your credentials in the          │
│   environment unless you remove them.                   │
└─────────────────────────────────────────────────────────┘
```

Everything Kryon offers operates *inside* that box. Nothing it offers makes the box smaller.
Making the box smaller is the job of the operating system: a container, a VM, a jail, an
unprivileged account, seccomp, AppArmor. See [sandboxing](sandboxing.md).

## What Kryon does defend against

### Command injection through arguments

`execute()` passes arguments to the operating system as a vector. There is no shell, no
parsing, no interpolation. An argument containing `; rm -rf /` is a filename with an unusual
name, and that is all it is.

This is the project's principal security property, and it is enforced by design rather than
by discipline: shell semantics live behind a
[separately named method](command-execution.md), so reaching for them is a visible decision
in a diff.

Conformance case: `execute.args.vector_is_literal`.

### Accidental credential inheritance

A child process inherits your environment by default, and your environment probably contains
secrets. `clear_env=True` combined with `env={...}` is an allowlist:

```python
runtime.execute("./build.sh", clear_env=True, env={"PATH": "/usr/bin:/bin"})
```

Conformance cases: `execute.env.cleared_is_allowlist`, `execute.env.null_removes`.

### Credential leakage through error messages

Kryon errors carry the executable name and an excerpt of stderr. They never carry the
environment, never carry stdin, and cap the stderr excerpt. An error message is the
most-copied, most-logged, most-pasted-into-a-public-issue string a library produces.

### Unbounded resource growth

`max_output_bytes` stops a process that floods its output, and stops it *while* it floods
rather than after buffering everything. `timeout` stops a process that hangs. Both are
liveness properties: they keep your process alive and responsive.

### Orphaned processes

Timeouts, cancellations and scope exits all terminate the child before returning control.
Kryon never hands you back the flow of execution while leaving a process running. A leaked
child outlives the program that spawned it and nobody notices until a machine runs out of
process slots.

Conformance cases: `spawn.scope_exit_terminates`, `execute.timeout.kills_signal_ignorer`.

### Misdirected commands

A `cwd` that does not exist is an error, never a silent fallback to the current directory.
Running the right command in the wrong directory is a data-corruption bug, not a crash, and
those are the expensive ones.

## What Kryon does **not** defend against

### It does not contain what it starts

Once a process is running it has your privileges. It can read your files, open sockets, spawn
its own children, and keep running after you kill its parent. Kryon's limits are cooperative:

| Mechanism | What it actually does | What it does not do |
|---|---|---|
| `timeout` | Sends a termination request, then kills | Prevent damage done before the kill |
| `max_output_bytes` | Stops reading, stops the process | Stop the process writing elsewhere |
| `clear_env` | Controls the environment Kryon passes | Stop the child reading files, sockets or `/proc` |
| `terminate()` | Stops that one process | Stop its children — see below |

### It does not kill process trees

Terminating a process does not terminate its descendants on any supported platform. Stop a
shell and what the shell started keeps running. Process groups (POSIX) and job objects
(Windows) can do this; Kryon does not use them yet, and this documentation will not imply
otherwise until it does.

### It does not make `execute_shell` safe

`execute_shell` reaches a real shell. Interpolating untrusted input into that string is a
command-injection vulnerability, and no amount of escaping in your application is a
substitute for not doing it. Kryon provides the method because sometimes you genuinely need
a pipeline; it provides it under a name you cannot type by accident.

### It does not authenticate anything

There is no authentication, authorization or policy layer in Kryon. If your application
decides which commands may run, your application enforces that. When
[remote transports](remote-execution.md) arrive, the same will be true: the transport carries
messages, and the application decides who may send them.

### It does not audit

Kryon has no logging, no telemetry and no network calls of its own. That is deliberate — see
[privacy](#privacy) — but it means an audit trail is something you build, not something you
get. Hooks for it are on the roadmap.

## Attacker scenarios

| Scenario | Outcome | Kryon's role |
|---|---|---|
| Untrusted value becomes an argument to `execute` | Safe; it is one literal argument | Defends |
| Untrusted value interpolated into `execute_shell` | **Full command execution as your user** | Cannot help; documented and named to discourage |
| Untrusted value chooses the *executable* | **Runs any program on the machine** | Cannot help; validate against an allowlist yourself |
| Untrusted value becomes a `cwd` | Runs your command somewhere unexpected | Validates existence only, not permission |
| Child reads `AWS_SECRET_ACCESS_KEY` from the environment | Secret disclosed | `clear_env` prevents it if you use it |
| Child forks and detaches | Survives your `terminate()` | Not addressed |
| Command floods stdout | Bounded memory, process stopped | Defends |
| Command hangs forever | Stopped at the timeout | Defends |
| Command is a fork bomb | **Machine degraded** | Not addressed; needs OS limits |
| Browser reaches an unauthenticated execution endpoint | **Total compromise** | Kryon must never be deployed this way — see [remote execution](remote-execution.md) |

## Privacy

Kryon makes no network calls. It has no telemetry, no analytics, no update check, no crash
reporting, and no ability to be given any of those by configuration. It does not log command
contents, because command lines routinely contain tokens.

If a future version needs to talk to a network, that will be a transport the application
explicitly constructs and points somewhere — never a default, never implicit.

## Reporting a vulnerability

Use GitHub's private vulnerability reporting: **[Security → Report a
vulnerability](https://github.com/PIYUSH-MISHRA-00/kryon/security/advisories/new)**. Do not
open a public issue for an undisclosed vulnerability. See [`SECURITY.md`](../../SECURITY.md).

## If you read nothing else

1. Never build an `execute_shell` string from untrusted input.
2. Never let untrusted input choose the executable.
3. Never expose command execution to a browser without authentication, authorization, a
   command allowlist, and OS-level isolation — [all four](remote-execution.md).
4. Kryon's timeouts and limits keep *your* process healthy. They do not contain a hostile
   one. That job belongs to a container, a VM or an unprivileged account.
