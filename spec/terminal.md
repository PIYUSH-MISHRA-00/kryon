# Terminal Specification

**Status:** Design · **Not implemented in any SDK** · targeted for `1.x`

This document records the intended model for PTY sessions and terminal emulation. It is a
design under review, not a contract anything currently satisfies. It is published now so
that the execution API does not accidentally foreclose it.

## 1. Two distinct things

"Terminal" routinely means two unrelated components, and conflating them is the most common
architectural mistake in this space:

**PTY session** — an operating-system facility. A pseudo-terminal pair that makes a child
process believe it is attached to a terminal, so that it line-buffers, emits colour, and
receives job-control signals. This is I/O plumbing. It produces a byte stream.

**Terminal emulator** — a pure state machine. It consumes that byte stream, interprets ANSI
and VT control sequences, and maintains a screen: a grid of cells, a cursor, attributes,
scrollback. It performs no I/O at all.

Kryon **MUST** keep these separable. A caller who wants a real interactive `bash` but renders
it with `xterm.js` needs the first without the second. A caller who wants to parse recorded
CI logs into a rendered screenshot needs the second without the first.

## 2. PTY sessions

```
runtime.open_pty(executable, arguments, size, options) -> PtySession
```

`PtySession` is a [`Process`](process.md) with three additions:

| Member | Meaning |
|---|---|
| `size` | Current terminal dimensions in rows and columns. |
| `resize(rows, cols)` | Change dimensions; delivers `SIGWINCH` to the child on POSIX. |
| `output` | A **single** merged stream. |

The single stream is not a simplification — a PTY genuinely has one output channel. The
child's stdout and stderr are the same file descriptor, and they cannot be separated after
the fact. SDKs **MUST NOT** offer a `stderr` on a PTY session that silently returns nothing.

### 2.1 Platform reality

| | Linux | macOS | Windows | Android | iOS | Browser |
|---|---|---|---|---|---|---|
| Mechanism | `openpty` | `openpty` | ConPTY | `openpty`, unprivileged | None | None |
| Resize | `TIOCSWINSZ` + `SIGWINCH` | Same | ConPTY resize API | Same as Linux | — | — |
| Job control | Full | Full | None | Partial | — | — |
| Requires native code | Yes | Yes | Yes | Yes | — | — |

Windows ConPTY (Windows 10 1809 and later) is not a POSIX PTY wearing a hat. It performs its
own terminal emulation inside the console host, which means output can arrive already
processed and differs from what the same program emits on Linux. The specification **MUST
NOT** promise byte-identical output across platforms, and conformance tests for PTY
**MUST** assert on behaviour, not on exact byte sequences.

iOS does not permit an application to spawn arbitrary child processes. There is no PTY
support to implement there; the correct answer for iOS is a remote transport
([`transport.md`](transport.md)), and documentation **MUST** say so plainly rather than
listing iOS as "planned".

Browsers cannot execute host processes at all. A browser SDK provides rendering and a
transport client; the process lives on a server.

## 3. Terminal emulator

```
terminal = Terminal(rows, cols)
terminal.feed(bytes) -> [TerminalEvent]
terminal.screen -> Screen
```

Pure, synchronous, no I/O, no threads. Feeding the same bytes to two instances **MUST**
produce identical screens — this is what makes it testable and what makes it usable from a
renderer on any thread.

### 3.1 Model

| Concept | Contents |
|---|---|
| `Screen` | The grid, the cursor, the active attributes, the scroll region. |
| `Cell` | One grapheme cluster plus its attributes. Wide characters occupy two cells. |
| `Cursor` | Row, column, visibility, shape. |
| `Attributes` | Foreground, background, bold, italic, underline, reverse, strike, hyperlink. |
| `Scrollback` | Lines that have scrolled off the top, bounded by a configured limit. |
| `AlternateScreen` | The secondary buffer used by full-screen programs; has no scrollback. |

Colour **MUST** be modelled as a union of the three real cases — 16 indexed, 256 indexed,
24-bit RGB — and **MUST NOT** be eagerly flattened to RGB. Themes remap indexed colours at
render time; a parser that flattens early destroys that.

### 3.2 Target compatibility

The emulator targets the `xterm-256color` subset in practical use: CSI, SGR, OSC, cursor
and screen manipulation, alternate screen, scroll regions, bracketed paste, mouse reporting.
It does not target completeness. Sequences that are not implemented **MUST** be consumed and
discarded silently — a parser that prints garbage on an unrecognised escape is worse than
one that ignores it — and **SHOULD** be countable for diagnostics.

Unicode is not optional: width calculation (`wcwidth` semantics), combining characters and
grapheme clustering are correctness requirements, not enhancements.

### 3.3 Input encoding

The reverse direction — turning a key press into the bytes a terminal program expects — is
part of the emulator, because it depends on emulator state (application cursor keys mode,
keypad mode, bracketed paste, modifier encoding). SDKs **MUST NOT** leave this to the
renderer, or every renderer will get it subtly wrong in a different way.

## 4. Why not just use an existing emulator?

Mature terminal emulator implementations exist and are excellent. Kryon's reason to specify
its own model is that it needs *one* model that behaves identically across five languages
and can be driven from a transport, not just from a local PTY. Where a target ecosystem has
a strong existing implementation, an SDK **MAY** wrap it rather than reimplement it,
provided it passes the conformance corpus. Reimplementation is not a goal in itself.
