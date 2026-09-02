# Why Kryon?

An honest answer, including the cases where the answer is "you don't need it".

Every project named here is good, widely used, and solves its problem well. This page
compares *architecture and use case*, not quality. If a comparison here is unfair or out of
date, [open an issue](https://github.com/PIYUSH-MISHRA-00/kryon/issues) — being wrong in
public about someone else's project is worse than being wrong about your own.

## When you should not use Kryon

**You run one command, occasionally, in one language.** `subprocess.run(["git", "status"],
capture_output=True)` is fine. It is in the standard library, it has no supply-chain risk,
and everyone reading your code already knows it. Adding a dependency to save three lines is
a bad trade — Kryon's own philosophy says so.

**You need a sandbox.** Kryon is not one and will not become one. Use a container or a VM.
See [sandboxing](../security/sandboxing.md).

**You need a terminal component for a web page today.** Use a mature browser terminal
component. Kryon's rendering layer does not exist, and when it does its goal will be to feed
good renderers rather than replace them.

**You need SSH.** Use a real SSH library. Kryon will eventually adapt one; it will never
implement the protocol itself.

## When Kryon starts to pay

The value is not in any single feature. It is in getting all of the following right at once,
in more than one language:

**You run commands from several languages.** A Python service, a Node CLI and a Kotlin backend
that all shell out will, with stock APIs, disagree about timeouts, environment handling, output
limits and error semantics. Kryon's proposition is one specified behaviour across all of them,
verified by a [shared conformance corpus](../../tests/conformance/cases.json) that all five SDKs
actually run. This is the reason the project exists, and the only one no single-language library
can match.

**You are streaming, not just capturing.** `capture_output=True` waits for the process to
finish. Showing a build's output live means dealing with two pipes concurrently, chunk
boundaries, backpressure, and cleanup on cancellation. That is the part people get wrong.

**Untrusted values reach your command lines.** Kryon makes the safe path the default one and
puts the dangerous path behind a
[different method name](../security/command-execution.md), so reaching for it is visible in
a code review.

**You need limits that hold.** A timeout that actually kills the process, including one that
ignores `SIGTERM`. An output cap that bounds memory *while* the flood happens rather than
after. Scope exit that never leaks a child.

**You want to grow into a terminal without rewriting.** Execution, PTY, emulation, transport
and rendering are [separate layers](../architecture/overview.md) sharing one model, so
adding a real interactive terminal later is not a second architecture.

## Compared with…

### Standard-library subprocess APIs

*`subprocess` (Python), `child_process` (Node), `Process` (Java/Dart), `ProcessBuilder`*

They are the foundation Kryon builds on — the Python SDK uses `subprocess` and
`asyncio.subprocess` directly, and adds no dependencies. What differs is what sits on top:

| | Standard library | Kryon |
|---|---|---|
| Shell by default | Varies; Node's `exec` yes, Python's no | Never, and no flag to change it |
| Timeout kills the process | Python yes; several others no | Always, with a documented grace period |
| Output limits | No | Per stream, enforced during the flood |
| Streaming both pipes | Manual, and easy to deadlock | Built in, with backpressure |
| Errors carry the result | Partly | Always |
| Cross-language consistency | None | Specified and tested |
| Dependencies | None | None |

For one command in one language, the standard library wins on simplicity. The gap opens with
streaming, limits and multiple languages.

### Process-execution convenience libraries

*`sh`, `plumbum`, `delegator`, `execa`, `zx`, `zt-exec`, `NuProcess`*

These make invocation ergonomic — pipelines, operator overloading, template literals — and
several are a genuine pleasure to use. Their focus is the ergonomics of *invocation*.
Kryon's focus is the *behaviour* of execution (limits, lifecycle, termination, error
taxonomy) and its consistency across languages. Some of them default to a shell, which is
the trade-off they make for ergonomics.

### PTY libraries

*`ptyprocess`, `node-pty`, `pty4j`, `pty.h` wrappers*

These solve layer 2, and solve it well; `node-pty` in particular is the reference for how
hard ConPTY is. Kryon does not have a PTY implementation yet. When it does, the difference
will be that PTY is one layer of a specified stack rather than a standalone concern — the
same session model, the same limits, the same errors, whether the session is a plain pipe, a
PTY, or a remote transport.

Until then: if you need a PTY today, use one of these.

### Terminal emulator components

*`xterm.js`, `vt100`/`pyte`, `alacritty_terminal`*

These solve layer 4, and mature ones are very good at it. Kryon's emulator is specified and
unimplemented. Its intended difference is being one pure state machine with identical
behaviour in five languages, drivable from a transport as well as from a local PTY.

Where a strong implementation already exists in a target ecosystem, an SDK is explicitly
allowed to *wrap* it rather than reimplement it, provided it passes conformance.
Reimplementation is not a goal in itself.

### Web terminal stacks

*`ttyd`, `GoTTY`, `Wetty`, `xterm.js` + a WebSocket server*

These are complete products: a browser terminal and a server to back it. If that is exactly
what you need, they will get you there faster than Kryon.

Kryon's intended role is different — a library your application embeds, where your
application owns authentication, authorization and policy. It will not ship a
ready-to-run "expose a shell on a port" server, because the shape that is easy to deploy is
the shape that ends up in incident reports. See
[remote execution](../security/remote-execution.md).

### Sandboxes and code-execution services

*gVisor, Firecracker, Docker, WebAssembly runtimes, hosted execution APIs*

Different layer entirely, and complementary. They provide the boundary; Kryon starts the
process correctly inside it. Kryon is not an alternative to any of them, and treating it as
one is the mistake the [threat model](../security/threat-model.md) exists to prevent.

## What Kryon has to earn

Nothing above is a claim of superiority, and the terminal half of the project is not implemented
yet. Today Kryon is: a carefully specified execution model, **five** SDKs that implement it and
agree, a shared conformance corpus, and a written architecture for the rest.

What it has not earned yet is users. A specification five implementations agree on is strong
evidence of internal consistency and no evidence at all that the model is right for your problem.
That evidence comes from people using it and saying where it is wrong.

The [roadmap](../../ROADMAP.md) says what is actually planned, and the
[status table](../../spec/README.md#implementation-status) says what actually exists.
