# Web Terminal Architecture

**There is no runnable web terminal example in this repository, and that is deliberate.**

The version that is fifteen lines long, works immediately and demos beautifully is remote
code execution as a service. Shipping it as an example would put it into production
somewhere within a week, because examples get copied.

This document is what the example would have to contain to be responsible.

## The shape

```
┌────────────────────────────────────────────────────────────┐
│ Browser                                                    │
│   Terminal rendering only. Executes nothing.               │
│   Sends keystrokes, receives bytes, draws a screen.        │
└──────────────────────────┬─────────────────────────────────┘
                           │ TLS WebSocket
                           │ authenticated, origin-checked,
                           │ one session per user
┌──────────────────────────▼─────────────────────────────────┐
│ Your application backend           ← the security layer    │
│   authn · authz · command policy · limits · audit          │
└──────────────────────────┬─────────────────────────────────┘
                           │
┌──────────────────────────▼─────────────────────────────────┐
│ Kryon runtime                                              │
│   argument vectors · timeout · output cap · env allowlist  │
└──────────────────────────┬─────────────────────────────────┘
                           │
┌──────────────────────────▼─────────────────────────────────┐
│ Isolated execution environment                             │
│   container or VM · unprivileged user · read-only root     │
│   no network · cgroup limits · destroyed after the session │
└────────────────────────────────────────────────────────────┘
```

**Kryon is the third box.** The security is in the second and fourth. Any tutorial showing
the third box alone is showing you a vulnerability with good typography.

## What the backend has to do

Not suggestions. A server missing any of these should not be exposed to a network.

### Authenticate before allocating anything

Verify identity before a session, a container or a process exists. Not a token in a query
string — those land in proxy logs, browser history and `Referer` headers. Use a header, or a
short-lived single-use ticket issued over an already-authenticated channel.

### Authorize per session

Authentication answers *who*. Authorization answers *may this person have a terminal, as
which user, in which directory, on which host*. Different questions; both need answering,
per session rather than once at login.

### Enforce a command policy, default deny

An allowlist of permitted executables. Not a denylist — denylists lose, always: there is
another way to spell `sh`, another interpreter installed, another binary that can exec.

If the product genuinely needs an arbitrary shell — a cloud IDE, a teaching sandbox — then
the allowlist is the isolated environment itself, and the isolation does all the work. Be
honest with yourself about which situation you are in.

### Bound every resource

Per session: wall-clock limit, idle timeout, output cap, memory, CPU. Per principal:
concurrent sessions and sessions per hour. Without a concurrency cap, one authenticated user
opens ten thousand sessions and takes the host down without breaking a single other rule.

### Audit

Who opened which session, from where, running what, when it closed, and why. Terminal
sessions are the highest-value audit trail in a system and the one most often missing after
an incident. Log metadata; think hard before logging command *content*, which routinely
contains credentials.

### Isolate at the operating system

Kryon's limits are cooperative. Real isolation is a container or VM per session, an
unprivileged user, a read-only root with a writable scratch mount, no network or an explicit
egress allowlist, cgroup limits on CPU/memory/PIDs, and seccomp or AppArmor where available.

Yes, that is heavy. It is the actual cost of running untrusted commands, and it does not go
away because the interface looks like a nice web terminal.

## Session lifecycle

| Event | What the backend must do |
|---|---|
| Connect | Authenticate. Reject before allocating anything. |
| Open | Authorize. Check concurrency caps. Create the isolated environment. Audit. |
| Input | Validate size, rate limit. Client input is bytes for a process's stdin — never a command to run. |
| Resize | Validate bounds. A terminal of 2³¹ rows is an attack, not a window. |
| Output | Apply the output cap. Apply backpressure; never buffer unboundedly for a slow client. |
| Idle | Close after an idle timeout. Abandoned sessions are the normal case. |
| Disconnect | Terminate the process. Destroy the environment. Audit. |
| Server restart | Terminate every session. Do not leak processes across a deploy. |

## The version that is a breach

```
Browser ──▶ unauthenticated WebSocket ──▶ Kryon ──▶ shell as the server user
```

If you find something resembling this anywhere in this repository, it is a security bug —
please [report it](https://github.com/PIYUSH-MISHRA-00/kryon/security/advisories/new).

## Where this stands today

Kryon has **no transport implementation**. The [transport
specification](../spec/transport.md) is a design, and it will not be implemented until the
security model above is settled and the wire protocol is specified and versioned.

When a WebSocket transport does arrive, it will ship with a reference server that has
authentication, authorization, a command allowlist, resource limits and audit hooks — or it
will not ship.

## Further reading

- [Remote execution](../docs/security/remote-execution.md) — the same ground, in more detail
- [Sandboxing](../docs/security/sandboxing.md) — what actually isolates a process
- [Threat model](../docs/security/threat-model.md) — what Kryon does and does not defend against
- [Transport specification](../spec/transport.md) — the interface, and §4 on security
