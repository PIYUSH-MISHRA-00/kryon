# Architecture Overview

Kryon is built as five layers that can be used independently. Most projects in this space
fuse two or three of them, and that fusion is what makes them hard to reuse.

```
┌──────────────────────────────────────────────────────────────┐
│ 5. Renderer            web · Flutter · JVM · native          │  planned
│    Draws a screen. Knows nothing about processes.            │
├──────────────────────────────────────────────────────────────┤
│ 4. Terminal emulator   ANSI/VT parser + screen state         │  specified
│    Pure state machine. Bytes in, screen out. No I/O.         │
├──────────────────────────────────────────────────────────────┤
│ 3. Transport           local · WebSocket · SSH               │  specified
│    Carries session messages. Local is direct calls.          │
├──────────────────────────────────────────────────────────────┤
│ 2. PTY engine          openpty · ConPTY                      │  specified
│    Makes a process believe it has a terminal.                │
├──────────────────────────────────────────────────────────────┤
│ 1. Execution engine    spawn · streams · signals · lifecycle │  IMPLEMENTED
│    Starts processes and manages them correctly.              │
└──────────────────────────────────────────────────────────────┘
```

Only layer 1 exists today, in Python. Layers 2–4 have written specifications; layer 5 has a
design intent. The [status table](../../spec/README.md#implementation-status) is authoritative.

## Why these boundaries

The layers are drawn where they are because each one is genuinely useful without the ones
above it, and each has a different reason to change.

**Execution without PTY.** Running `git status` and reading its output needs no terminal at
all. This is the overwhelmingly common case, and it should not carry the cost or the platform
complexity of pseudo-terminal allocation.

**PTY without emulation.** A web terminal that renders with an existing component needs a
real PTY and a byte stream; it does not need Kryon to parse those bytes, because the browser
component already does.

**Emulation without PTY.** Turning a recorded CI log into a rendered screen is pure parsing.
No process, no file descriptor, no platform code. Keeping the emulator pure makes it
testable by feeding bytes and comparing screens — and testable is the only way a VT parser
ever becomes correct.

**Transport under all of it.** If the layers above are defined in terms of operations and
events rather than system calls, the same code works when the process is on another machine.
That was a design constraint from the first line, not a later refactor.

## Layer 1 — Execution engine

The part that exists. Specified in [`spec/execution.md`](../../spec/execution.md) and
[`spec/process.md`](../../spec/process.md).

Two operations:

- `execute(exe, args, options) -> ExecutionResult` — run to completion, buffer the output,
  return everything. For when you want the answer.
- `spawn(exe, args, options) -> Process` — start it, return immediately, stream and signal
  while it runs. For when you want a conversation.

They are separate because a blocking call cannot express an interactive session, and because
bolting callbacks onto a one-shot call produces an API where nobody owns the process's
lifetime. `execute` is specified as expressible in terms of `spawn`; implementations may
take a simpler buffered path as long as the observable behaviour is identical, which the
conformance corpus checks.

Design decisions this layer makes, and why, are in
[the overview](../getting-started/overview.md#three-decisions-worth-knowing-up-front).

## Layer 2 — PTY engine

Allocates a pseudo-terminal so a child process believes it is attached to a real one — which
changes how it buffers, whether it emits colour, and whether it receives job-control signals.

Platform reality is unavoidable here: `openpty` on Unix, ConPTY on Windows, nothing at all on
iOS or in a browser. ConPTY is not a POSIX PTY with a different name — it performs its own
terminal emulation inside the console host, so the same program produces different bytes.
This layer's contract promises *behaviour*, never byte-identical output.

Specified in [`spec/terminal.md`](../../spec/terminal.md) §2. Not implemented.

## Layer 3 — Transport

The indirection that lets a session run somewhere else. Four operations — `open`, `send`,
`receive`, `close` — and a small message set. If a transport needs a fifth operation, the
core model is wrong and the core model changes.

The local transport is not a network protocol; it is direct calls, and it is what the Python
SDK uses today implicitly.

Specified in [`spec/transport.md`](../../spec/transport.md). Not implemented. The
[security requirements](../security/remote-execution.md) for a remote transport server are
non-negotiable and written down before any code exists, deliberately.

## Layer 4 — Terminal emulator

A pure, synchronous state machine: feed it bytes, get a screen. No I/O, no threads, no
platform code. Feeding identical bytes to two instances must produce identical screens —
that property is what makes it testable and what lets a renderer drive it from any thread.

Two details that are correctness requirements rather than refinements: colour is modelled as
a union of indexed-16, indexed-256 and RGB rather than flattened early (themes remap indexed
colours at render time), and Unicode width, combining characters and grapheme clustering are
handled properly (a terminal that gets `wcwidth` wrong corrupts every screen containing CJK
text or an emoji).

Specified in [`spec/terminal.md`](../../spec/terminal.md) §3. Not implemented.

## Layer 5 — Renderer

Draws the screen from layer 4, sends key events back to it. Platform-specific by nature: DOM
or canvas on the web, widgets in Flutter, Compose or Swing on the JVM.

Kryon does not intend to compete with mature web terminal components. Where a good renderer
exists, the goal is to feed it, not replace it.

## Cross-cutting: the SDK contract

Five language SDKs, one product. That works only because the behaviour is specified centrally
and verified by a [shared conformance corpus](../../tests/conformance/cases.json) that every
SDK runs against its own implementation. Without it, five teams produce five subtly different
timeout semantics and nobody notices for two years.

How that is kept honest is in [SDK design](sdk-design.md).

## What is deliberately absent

**A plugin system.** There is one extension point — the transport interface — and it exists
because remote execution genuinely requires it. Nothing else is pluggable, because nothing
else has two implementations yet, and an interface with one implementation is a guess about
the future written in code.

**Global state.** No singletons, no ambient runtime, no process registry. A `Runtime` holds
configuration; processes are owned by their handles. Two libraries in the same application
using Kryon must not be able to interfere with each other.

**A configuration file format.** Options are passed as arguments. A library that reads a
config file from disk makes its behaviour depend on something the calling application cannot
see.
