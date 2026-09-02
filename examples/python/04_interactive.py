"""Holding a conversation with a long-lived process.

    python examples/python/04_interactive.py

Write, read, write again. A blocking `execute` cannot express this, which is why `spawn`
is a separate operation rather than a flag.
"""

import sys

from kryon import Runtime, Stream

runtime = Runtime()

# A tiny request/response worker: read a line, answer it, repeat until end-of-input.
WORKER = """
import sys
for line in sys.stdin:
    job = line.strip()
    if not job:
        continue
    if job == "fail":
        print("cannot do that", file=sys.stderr, flush=True)
        continue
    print(f"result: {job.upper()}", flush=True)
print("worker exiting", flush=True)
"""

with runtime.spawn(sys.executable, ["-u", "-c", WORKER]) as proc:
    for job in ("first", "second", "fail", "third"):
        proc.write(f"{job}\n")

    # Closing stdin is what tells the worker there is no more input. Without it, a process
    # reading until end-of-input waits forever, and the hang looks like Kryon's fault.
    proc.close_stdin()

    for stream, chunk in proc:
        where = "err" if stream is Stream.STDERR else "out"
        for line in chunk.decode().splitlines():
            print(f"[{where}] {line}")

    result = proc.wait()

print(f"\nexit code {result.exit_code}")

# ---------------------------------------------------------------- the caveats

print("""
Two limitations to know about:

  Interleaving between stdout and stderr is approximate. They are two separate pipes;
  the order you see is the order Kryon observed them arrive, which is not necessarily
  the order the child wrote them.

  A REPL will probably not behave like a REPL. Programs that print a prompt usually
  detect that they are not attached to a terminal and change behaviour -- no prompt, full
  buffering, no colour. That is what PTY support is for, and it is not implemented yet.
  See spec/terminal.md.
""")

# `wait()` gives you the outcome, not the output: the output was streamed to you above and
# is deliberately not buffered a second time.
print(f"result.stdout is empty by design: {result.stdout!r}")
