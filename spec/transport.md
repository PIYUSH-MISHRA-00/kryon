# Transport Specification

**Status:** Design — `0.1` · **Not implemented in any SDK**

Defines how a Kryon session can run somewhere other than the local machine. This document
is a design under review; no transport other than the implicit local one exists today.

## 1. The idea

Everything in [`execution.md`](execution.md) and [`process.md`](process.md) is described in
terms of operations and events, not in terms of system calls. That was deliberate: the same
operations can be carried over a wire.

```
Runtime ──▶ Transport ──▶ session
              ├── local      in-process, direct system calls  (implemented)
              ├── websocket  browser or client ⇄ authenticated backend  (design)
              └── ssh        adapter over an SSH channel  (design)
```

A `Runtime` holds a transport. The default is the local one. Swapping it changes where
processes run, not how callers write code.

## 2. Interface

A transport implements a deliberately small surface:

| Operation | Meaning |
|---|---|
| `open(request)` | Start a session for an execution or spawn request; return a handle. |
| `send(handle, message)` | Deliver input, a resize, or a signal to the session. |
| `receive(handle)` | A stream of session events: output chunks, exit, error. |
| `close(handle)` | End the session and release its resources. |

Everything a session needs is expressible in those four operations. If a transport needs a
fifth, that is evidence the core model is wrong and the core model should change — not
evidence the interface should grow.

## 3. Messages

| Direction | Message | Payload |
|---|---|---|
| → | `Start` | executable, arguments, options |
| → | `Input` | bytes for stdin |
| → | `CloseInput` | none |
| → | `Resize` | rows, columns |
| → | `Signal` | signal number or symbolic name |
| ← | `Started` | pid where meaningful |
| ← | `Output` | stream tag, bytes |
| ← | `Exited` | exit code, signal, termination reason |
| ← | `Error` | error class, message |

The wire encoding is not yet specified. It will be, before any transport is implemented,
and it will be versioned independently of the SDKs so that a client and server on different
SDK versions can negotiate.

## 4. Security — read this before implementing anything here

A remote transport turns Kryon from a library that runs commands on behalf of the process
that called it into a service that runs commands on behalf of whoever can reach the socket.
That is a categorically different risk, and the transport layer is where the project is most
likely to hurt someone.

### 4.1 What a transport server MUST do

A Kryon transport server **MUST NOT** be shipped, documented, or exemplified without:

1. **Authentication** before a session may be opened. Not a token in a query string.
2. **Authorization** per session: which commands, which working directories, which user.
3. **An explicit command policy.** Default deny. An allowlist of executables the caller may
   run, not a denylist of ones they may not — denylists lose.
4. **Isolation.** A container, a VM, a jail, or a dedicated unprivileged account. Kryon's
   own limits are not isolation.
5. **Resource limits.** Timeout, output cap, concurrent sessions per principal, total
   session lifetime.
6. **Audit hooks.** Who opened what session, running what, when, from where.

### 4.2 What Kryon will never claim

Kryon is not a sandbox. It has no ability to constrain what a process does once that process
is running. Every limit it offers — timeout, output cap, session cap — is a liveness and
resource-management mechanism that a cooperative process respects and a hostile one works
around.

Any documentation, example or website copy that implies otherwise is a bug.

### 4.3 The shape that is correct

```
Browser (rendering only, no execution)
  │  authenticated TLS WebSocket, per-user session
  ▼
Your application backend  ── authn, authz, command policy, rate limit, audit
  │
  ▼
Kryon runtime  ── timeout, output cap, environment control
  │
  ▼
Isolated process or PTY, in a container, as an unprivileged user
```

Every layer is the application's responsibility except the third. Kryon documentation
**MUST** present the whole diagram, not just its own box, because the box alone is not a
system anyone should deploy.

### 4.4 The shape that is a vulnerability

```
Browser ──▶ unauthenticated WebSocket ──▶ Kryon ──▶ shell as the server user
```

This will not be shipped as an example, a quickstart, a demo, or a test fixture. It is the
single most likely way for this project to end up in someone's incident report.

## 5. SSH

SSH is listed as a future adapter, and only as an adapter over an existing, audited SSH
implementation. Kryon **MUST NOT** implement the SSH protocol, key exchange, or host-key
verification itself. Host-key verification in particular **MUST** be on by default with no
convenience flag to disable it silently.

This work does not begin until the local implementation is complete and the security model
above is settled. Implementing SSH because the feature list looks better with it in is how
projects ship credential handling nobody reviewed.
