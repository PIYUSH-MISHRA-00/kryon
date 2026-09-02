"""Reading output while a process is still running.

    python examples/python/03_streaming.py

This is the reason `spawn` exists. `execute` waits for the process to finish before it
gives you anything, which is fine for `git rev-parse` and useless for a build.
"""

import sys
import time

from kryon import Runtime, Stream

runtime = Runtime()

# `-u` is not decoration. A child that buffers its output sends nothing until it exits, and
# that looks exactly like Kryon being broken when it is not. Most programs buffer when they
# detect they are not attached to a terminal; this is the single most common surprise when
# streaming, and it is a property of the program, not of Kryon.
WORKER = """
import sys, time
for n in range(5):
    print(f"step {n}", flush=True)
    if n == 2:
        print("something looked odd", file=sys.stderr, flush=True)
    time.sleep(0.3)
print("done", flush=True)
"""

print("output as it arrives:\n")
started = time.monotonic()

with runtime.spawn(sys.executable, ["-u", "-c", WORKER]) as proc:
    for stream, chunk in proc:
        where = "err" if stream is Stream.STDERR else "out"
        elapsed = time.monotonic() - started
        for line in chunk.decode().splitlines():
            print(f"  +{elapsed:4.1f}s [{where}] {line}")

    result = proc.wait()

print(f"\nexit code {result.exit_code} after {result.duration:.1f}s")
print("Note the timestamps: chunks arrived during the run, not at the end.")

# ---------------------------------------------------------------- what to know

print("""
Three things worth knowing about the stream:

  Chunk boundaries mean nothing. They reflect how the operating system delivered the
  bytes, not lines or records. Do not assume a chunk is a line.

  Backpressure is real. The queue behind `output` is bounded: stop consuming and Kryon
  stops reading, the pipe fills, and the child blocks. That is not a hang -- a producer
  faster than its consumer has to be slowed down somewhere, and the kernel buffer is a
  much better place than your heap.

  The scope owns the process. Leaving the `with` block terminates it and closes every
  pipe, whether you left normally, by `return`, or by exception.
""")

# ------------------------------------------------ leaving the scope stops it

proc = runtime.spawn(sys.executable, ["-c", "import time; time.sleep(300)"])
pid = proc.pid
with proc:
    print(f"pid {pid} running: {proc.running}")
print(f"pid {pid} running after the block: {proc.running}")
