# Remote Execution

**Status:** No transport is implemented. This document exists so that when one is, it is
built and deployed correctly — and so that nobody builds the dangerous version in the
meantime.

A browser-facing terminal is the most attractive thing you can build with Kryon and the
easiest way to hand an attacker a shell on your server. The difference between the two is
entirely in the layers around Kryon, not in Kryon.

## The architecture that is correct

```
┌──────────────────────────────────────────────────┐
│ Browser                                          │
│   Terminal rendering only. Executes nothing.     │
└───────────────────────┬──────────────────────────┘
                        │  authenticated TLS WebSocket
                        │  one session per user, scoped token
┌───────────────────────▼──────────────────────────┐
│ Your application backend        ← the security   │
│                                   layer          │
│   • authentication (who is this?)                │
│   • authorization (may they have a terminal?)    │
│   • command policy (default deny, allowlist)     │
│   • rate limiting, session caps                  │
│   • audit logging                                │
└───────────────────────┬──────────────────────────┘
                        │
┌───────────────────────▼──────────────────────────┐
│ Kryon runtime                                    │
│   • argument-vector execution                    │
│   • timeout, output cap, environment allowlist   │
└───────────────────────┬──────────────────────────┘
                        │
┌───────────────────────▼──────────────────────────┐
│ Isolated execution environment                   │
│   container / VM / jail, unprivileged user,      │
│   read-only root, no host network, cgroup limits │
└──────────────────────────────────────────────────┘
```

Four layers, and **Kryon is only the third**. The security lives in the second and fourth.
Any document, demo or blog post that shows the third layer alone is showing you a
vulnerability with good typography.

## The architecture that is a breach

```
Browser ──▶ unauthenticated WebSocket ──▶ Kryon ──▶ shell as the server user
```

This will never appear in this repository as an example, a quickstart, a demo or a test
fixture. It is fifteen lines of code, it works immediately, it demos beautifully, and it is
remote code execution as a service.

If you find something in this project that resembles it, that is a security bug — please
[report it](https://github.com/PIYUSH-MISHRA-00/kryon/security/advisories/new).

## Requirements for a Kryon transport server

Not suggestions. A server missing any of these should not be exposed to a network.

### 1. Authenticate before the session opens

Verify identity before allocating anything. Not a token in a query string — those end up in
proxy logs, browser history and `Referer` headers. Use a header, or a short-lived
single-use ticket exchanged over an already-authenticated channel.

### 2. Authorize per session

Authentication answers *who*. Authorization answers *may this person have a terminal, as
which user, in which directory, on which host*. They are different questions and both need
answering.

### 3. Enforce a command policy, default deny

An **allowlist** of permitted executables. Not a denylist — denylists lose, always: there is
always another way to spell `sh`, another interpreter installed, another binary that can
exec.

If the product genuinely requires an arbitrary shell (a cloud IDE, a teaching sandbox), then
the allowlist is the whole isolated environment, and requirement 6 does all the work. Be
honest with yourself about which situation you are in.

### 4. Bound every resource

Per session: wall-clock limit, idle timeout, output cap, memory, CPU. Per principal:
concurrent sessions, sessions per hour. Without a concurrency cap, one authenticated user
can open ten thousand sessions and take the host down without violating a single other rule.

### 5. Audit everything

Who opened which session, from which address, running what, when it closed, why. Terminal
sessions are the highest-value audit trail in a system, and the one most likely to be missing
after an incident. Record command *metadata*; think carefully before recording command
*content*, which routinely contains credentials.

### 6. Isolate at the operating system

Kryon's limits are cooperative. Real isolation is:

- a container or VM per session, destroyed at the end;
- an unprivileged user with no `sudo` and no access to other sessions' data;
- a read-only root filesystem with a writable scratch mount;
- no network, or an explicit egress allowlist;
- cgroup limits on CPU, memory, PIDs and disk;
- seccomp/AppArmor/SELinux where available.

Yes, this is heavy. That is the actual cost of running untrusted commands, and it does not
go away because the interface is a nice-looking web terminal.

## Session lifecycle

| Event | Requirement |
|---|---|
| Connect | Authenticate. Reject before allocating anything. |
| Open session | Authorize. Check concurrency caps. Allocate the isolated environment. Audit. |
| Input | Validate size. Rate limit. Never interpret client input as a command to run — it is bytes for a process's stdin. |
| Resize | Validate bounds. A terminal size of 2³¹ rows is an attack, not a window. |
| Output | Apply the output cap. Apply backpressure; never buffer unboundedly for a slow client. |
| Idle | Close after an idle timeout. Abandoned sessions are the normal case, not the exception. |
| Disconnect | Terminate the process. Destroy the environment. Audit. |
| Server shutdown | Terminate every session. Do not leak processes across a restart. |

## SSH

SSH is a listed future adapter and comes with a hard constraint: Kryon will **not** implement
the SSH protocol, key exchange or host-key verification. It will adapt an existing, audited
implementation.

Host-key verification will be on by default, with no convenience flag to disable it silently.
The number of SSH clients shipped with `StrictHostKeyChecking=no` as a default is the reason
this sentence exists.

See [`spec/transport.md`](../../spec/transport.md) §5.

## Before you deploy

- [ ] Authentication happens before any resource is allocated.
- [ ] Authorization is checked per session, not once at login.
- [ ] There is an executable allowlist, or full OS-level isolation, or both.
- [ ] Session count, duration, output and memory are all capped, per user.
- [ ] Every session start and end is audited with the principal and the source address.
- [ ] Each session runs in a container or VM as an unprivileged user, destroyed afterwards.
- [ ] The WebSocket is TLS-only, with origin checks.
- [ ] Nothing about the browser's input is trusted, including terminal dimensions.
- [ ] You have decided what happens when the process outlives the socket.
