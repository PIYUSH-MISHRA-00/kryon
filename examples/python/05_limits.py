"""Timeouts and output caps, and what they actually guarantee.

    python examples/python/05_limits.py

Short version: they keep *your* process healthy. They do not contain a hostile one.
See docs/security/threat-model.md.
"""

import sys

from kryon import ProcessTimeout, ResourceLimitExceeded, Runtime

runtime = Runtime()

# ------------------------------------------------------------------ timeouts

result = runtime.execute(sys.executable, ["-c", "import time; time.sleep(60)"], timeout=1)

print(f"termination: {result.termination.value}")
print(f"exit code:   {result.exit_code}")
print(f"duration:    {result.duration:.2f}s")
print("""
The process is gone, not merely abandoned. Kryon sends the platform's termination
request, waits `kill_grace` seconds, then kills. It never returns control to you while
leaving a child running.

Note the termination reason. At the kernel level this process was killed by a signal,
but TIMEOUT is what you need in order to decide whether to retry, so that is what is
reported.
""")

# -------------------------------------------------- partial output is preserved

CHATTY = "import time\nfor n in range(1000):\n    print(f'line {n}', flush=True)\n    time.sleep(0.01)\n"

result = runtime.execute(sys.executable, ["-u", "-c", CHATTY], timeout=1, encoding="utf-8")
first = result.stdout.splitlines()[:1]
print(f"output kept after the timeout: {first} ... ({len(result.stdout)} chars)")
print("  Discarding it would make every timeout undebuggable.\n")

# -------------------------------------------------------------- output limits

FLOOD = "import sys\nchunk = b'x' * 65536\nwhile True:\n    sys.stdout.buffer.write(chunk)\n"

result = runtime.execute(sys.executable, ["-c", FLOOD], max_output_bytes=4096, timeout=30)

print(f"termination:      {result.termination.value}")
print(f"bytes kept:       {len(result.stdout)}")
print(f"truncated flag:   {result.stdout_truncated}")
print("""
The cap bounds memory *while* the flood happens. An implementation that buffered
everything and truncated at the end would have enforced nothing at all -- which is the
usual way this feature is implemented, and the reason it is worth stating.
""")

# ----------------------------------------------------- distinguishable failures

for label, call in (
    ("timeout", lambda: runtime.execute(sys.executable, ["-c", "import time; time.sleep(60)"], timeout=1, check=True)),
    ("output limit", lambda: runtime.execute(sys.executable, ["-c", FLOOD], max_output_bytes=4096, timeout=30, check=True)),
):
    try:
        call()
    except (ProcessTimeout, ResourceLimitExceeded) as exc:
        print(f"{label:14} -> {type(exc).__name__}")

print("""
Each reason raises its own error, so a caller can retry a timeout without retrying a
genuine failure, or raise a limit without debugging the command. Every one of them
carries the partial result.
""")

# --------------------------------------------------------------- the honest bit

print("""What these are NOT
------------------
  A timeout limits duration, not damage. `rm -rf` finishes well inside one second.
  An output cap stops Kryon reading; it does not stop the child writing to disk or
  to a socket.
  Neither stops a process that forks and detaches -- terminating a process does not
  terminate its descendants on any platform Kryon supports.

Real containment is a container, a VM, or an unprivileged account.
See docs/security/sandboxing.md.""")
