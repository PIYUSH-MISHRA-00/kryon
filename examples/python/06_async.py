"""Concurrency and cancellation with asyncio.

    python examples/python/06_async.py

Identical semantics to the synchronous runtime -- both are checked against the same
conformance corpus -- with native cancellation on top.
"""

import asyncio
import sys
import time

from kryon import Stream
from kryon.aio import AsyncRuntime


async def concurrent(runtime: AsyncRuntime) -> None:
    """Five processes at once, in about the time one of them takes."""
    started = time.monotonic()

    results = await asyncio.gather(
        *(
            runtime.execute(
                sys.executable, ["-c", f"import time; time.sleep(0.5); print('task {n}')"]
            )
            for n in range(5)
        )
    )

    elapsed = time.monotonic() - started
    print(f"five 0.5s processes finished in {elapsed:.2f}s")
    print("output:", [r.stdout.strip() for r in results])


async def cancellation(runtime: AsyncRuntime) -> None:
    """Cancelling the task kills the child before CancelledError reaches you."""
    task = asyncio.ensure_future(
        runtime.execute(sys.executable, ["-c", "import time; time.sleep(300)"])
    )

    await asyncio.sleep(0.3)
    started = time.monotonic()
    task.cancel()

    try:
        await task
    except asyncio.CancelledError:
        print(f"\ncancelled and the child was terminated in {time.monotonic() - started:.2f}s")
        print("  Kryon never hands control back with a process still running -- not on a")
        print("  timeout, not on a cancellation, not on an exception unwinding the stack.")


async def streaming(runtime: AsyncRuntime) -> None:
    """Async streaming, with backpressure from the bounded queue."""
    worker = "import time\nfor n in range(4):\n    print(f'chunk {n}', flush=True)\n    time.sleep(0.2)\n"

    proc = await runtime.spawn(sys.executable, ["-u", "-c", worker])
    async with proc:
        async for stream, chunk in proc:
            where = "err" if stream is Stream.STDERR else "out"
            print(f"  [{where}] {chunk.decode().strip()}")
        result = await proc.wait()

    print(f"exit code {result.exit_code}")


async def timeout_still_applies(runtime: AsyncRuntime) -> None:
    result = await runtime.execute(
        sys.executable, ["-c", "import time; time.sleep(60)"], timeout=1
    )
    print(f"\ntimeout in async: {result.termination.value} after {result.duration:.2f}s")


async def main() -> None:
    runtime = AsyncRuntime(encoding="utf-8")

    await concurrent(runtime)
    await cancellation(runtime)
    print("\nstreaming:")
    await streaming(runtime)
    await timeout_still_applies(runtime)


if __name__ == "__main__":
    asyncio.run(main())
