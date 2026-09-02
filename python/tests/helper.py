"""The Python implementation of the Kryon conformance helper.

Implements the verb contract in ``spec/conformance.md`` §2. Every SDK ships one of these
in its own language so that the shared corpus in ``tests/conformance/cases.json`` means
the same thing everywhere -- ``echo``, ``sleep`` and ``/bin/sh`` do not, and half of them
do not exist on Windows.

Deliberately dependency-free and deliberately unbuffered: a helper that buffers turns
streaming tests into false failures.
"""

from __future__ import annotations

import os
import sys
import time

OUT = sys.stdout.buffer
ERR = sys.stderr.buffer


def _write(sink, text: str) -> None:
    sink.write(text.encode("utf-8"))
    sink.flush()


def main(argv: list) -> int:
    if not argv:
        _write(ERR, "helper: no verb given\n")
        return 64

    verb, args = argv[0], argv[1:]

    if verb == "echo":
        _write(OUT, " ".join(args) + "\n")
    elif verb == "raw":
        _write(OUT, args[0] if args else "")
    elif verb == "err":
        _write(ERR, " ".join(args) + "\n")
    elif verb == "both":
        _write(OUT, args[0] + "\n")
        _write(ERR, args[1] + "\n")
    elif verb == "exit":
        return int(args[0])
    elif verb == "env":
        _write(OUT, os.environ.get(args[0], "") + "\n")
    elif verb == "dumpenv":
        for key in sorted(os.environ):
            _write(OUT, f"{key}={os.environ[key]}\n")
    elif verb == "cwd":
        _write(OUT, os.getcwd() + "\n")
    elif verb == "sleep":
        time.sleep(float(args[0]))
    elif verb == "spam":
        remaining = int(args[0])
        block = b"x" * min(remaining, 65536)
        while remaining > 0:
            chunk = block[:remaining]
            OUT.write(chunk)
            remaining -= len(chunk)
        OUT.flush()
    elif verb == "cat":
        while True:
            data = sys.stdin.buffer.read(4096)
            if not data:
                break
            OUT.write(data)
            OUT.flush()
    elif verb == "lines":
        count, delay = int(args[0]), float(args[1])
        for n in range(count):
            _write(OUT, f"line {n}\n")
            time.sleep(delay)
    elif verb == "unicode":
        _write(OUT, "héllo · 世界 · \U0001f680\n")
    elif verb == "ansi":
        _write(OUT, "\x1b[31mred\x1b[0m\n")
    elif verb == "ignoreterm":
        # POSIX only; on Windows there is no signal to ignore and TerminateProcess wins.
        if os.name != "nt":
            import signal

            signal.signal(signal.SIGTERM, signal.SIG_IGN)
        time.sleep(float(args[0]))
    else:
        _write(ERR, f"helper: unknown verb {verb!r}\n")
        return 64
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
