# Examples

Every example here runs. Snippets that only exist in prose rot silently; files that run get
noticed when they break.

## Python

```bash
pip install -e ../python          # or: pip install kryon, once published
python examples/python/01_execute.py
```

| File | Shows |
|---|---|
| [`01_execute.py`](python/01_execute.py) | Running a command, exit codes, `check`, and why a missing executable raises |
| [`02_environment_and_cwd.py`](python/02_environment_and_cwd.py) | Inheriting, merging, removing and allowlisting environment variables; working directories |
| [`03_streaming.py`](python/03_streaming.py) | Output as it is produced, both streams tagged, and backpressure |
| [`04_interactive.py`](python/04_interactive.py) | A conversation with a long-lived process |
| [`05_limits.py`](python/05_limits.py) | Timeouts, output caps, and what termination reasons mean |
| [`06_async.py`](python/06_async.py) | Concurrent execution and cancellation that actually kills the child |

They run on Linux, macOS and Windows, and they invoke `python` itself rather than `echo` or
`ls`, so they behave the same everywhere and depend on nothing being installed.

## Architecture

| File | Shows |
|---|---|
| [`web-terminal-architecture.md`](web-terminal-architecture.md) | How a browser-facing terminal must be built — and why the easy version is not in this directory |

## What you will not find here

**A runnable web terminal server.** The version that is fifteen lines and demos beautifully
is remote code execution as a service. The architecture document explains what the real thing
requires, and Kryon will not ship the shortcut as an example.

**Examples for the other SDKs.** They do not exist yet. This directory will not contain
`examples/dart/` until there is a Dart SDK to run it.

## A reminder

Kryon is not a sandbox. These examples run commands with your privileges. Before adapting any
of them to handle input you do not control, read the
[threat model](../docs/security/threat-model.md).
