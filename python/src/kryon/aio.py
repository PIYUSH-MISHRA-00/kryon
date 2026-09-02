"""The asynchronous Kryon runtime.

Mirrors :mod:`kryon.runtime` with ``async``/``await``. The semantics are identical --
both are checked against the same conformance corpus -- but the implementations are
separate, because a thread-based sync runtime and a coroutine-based async one have
genuinely different plumbing and pretending otherwise produces the worst of both.

Cancellation is native: cancelling the task running :meth:`AsyncRuntime.execute`
terminates the child process before the ``CancelledError`` propagates. Kryon never
returns control while leaving a process running.
"""

from __future__ import annotations

import asyncio
import contextlib
import time
from collections.abc import AsyncIterator, Mapping, Sequence
from types import TracebackType
from typing import Any, Optional, Tuple, Type

from kryon import _core
from kryon._core import CHUNK, QUEUE_DEPTH, Buffer, Reason
from kryon.errors import InvalidArguments, ProcessTimeout, UnsupportedPlatform
from kryon.model import ExecutionOptions, ExecutionResult, Stream, TerminationReason

__all__ = ["AsyncProcess", "AsyncRuntime"]

_EOF = object()


class AsyncRuntime:
    """The async twin of :class:`kryon.Runtime`. Holds defaults, no state.

    ::

        runtime = AsyncRuntime(timeout=30, encoding="utf-8")
        result = await runtime.execute("git", ["status", "--porcelain"])
    """

    def __init__(self, **defaults: Any) -> None:
        self._defaults = ExecutionOptions().merge(defaults)
        self._defaults.validate()

    @property
    def defaults(self) -> ExecutionOptions:
        """The options this runtime applies when a call does not override them."""
        return self._defaults

    def __repr__(self) -> str:  # pragma: no cover - cosmetic
        return f"AsyncRuntime(defaults={self._defaults!r})"

    async def execute(
        self, executable: Any, arguments: Optional[Sequence[Any]] = None, **options: Any
    ) -> ExecutionResult:
        """Run ``executable`` with ``arguments`` and return what happened.

        Same contract as :meth:`kryon.Runtime.execute`, including the rule that failing
        to start raises and failing while running is returned.
        """
        return await self._execute(executable, arguments, options, shell=False)

    async def execute_shell(self, command_line: str, **options: Any) -> ExecutionResult:
        """Run ``command_line`` through the system shell.

        .. warning::
           Interpolating untrusted input into this string is a command-injection
           vulnerability. Use :meth:`execute` with an argument list instead.
        """
        if not isinstance(command_line, str):
            raise InvalidArguments(
                f"command_line must be a string, got {type(command_line).__name__}"
            )
        return await self._execute(command_line, None, options, shell=True)

    async def _execute(
        self,
        executable: Any,
        arguments: Optional[Sequence[Any]],
        overrides: Mapping[str, Any],
        shell: bool,
    ) -> ExecutionResult:
        opts = self._defaults.merge(overrides)
        opts.validate()
        exe, args = _core.normalise(executable, arguments)
        _core.check_cwd(opts.cwd)

        started = time.monotonic()
        proc = await _start(exe, args, opts, shell)
        reason = Reason()
        out, err = Buffer(opts.max_output_bytes), Buffer(opts.max_output_bytes)

        workers = [
            asyncio.ensure_future(_drain(proc.stdout, out, reason, proc, opts)),
            asyncio.ensure_future(_drain(proc.stderr, err, reason, proc, opts)),
            asyncio.ensure_future(_feed(proc.stdin, _encode_stdin(opts))),
        ]
        try:
            await asyncio.wait_for(proc.wait(), timeout=opts.timeout)
        except asyncio.TimeoutError:
            reason.set(TerminationReason.TIMEOUT)
            await _stop(proc, opts.kill_grace)
        except asyncio.CancelledError:
            reason.set(TerminationReason.CANCELLED)
            await _stop(proc, opts.kill_grace)
            raise
        finally:
            # `proc.wait()` returns the moment the child exits, which says nothing about
            # whether its output has been read. Anything still sitting in the pipe is only
            # reachable through the drains, so they get a chance to reach EOF before being
            # cancelled -- cancelling first discards that output and returns empty stdout
            # for a command that plainly wrote to it.
            #
            # Bounded, and for the same reason the sync runtime bounds its join: a
            # grandchild can hold the pipes open after the child dies, so EOF may never
            # come, and `sh -c "sleep 100 &"` would otherwise hang the caller. Same bound
            # as the sync path, because these are the same trade-off.
            _, pending = await asyncio.wait(workers, timeout=max(opts.kill_grace, 1.0))
            for worker in pending:
                worker.cancel()
            await asyncio.gather(*workers, return_exceptions=True)

        termination, exit_code, sig = _core.classify(proc.returncode, reason.value)
        result = ExecutionResult(
            executable=exe,
            arguments=tuple(args),
            exit_code=exit_code,
            signal=sig,
            stdout=_core.decode(out.value(), opts.encoding),
            stderr=_core.decode(err.value(), opts.encoding),
            duration=time.monotonic() - started,
            termination=termination,
            pid=proc.pid,
            stdout_truncated=out.truncated,
            stderr_truncated=err.truncated,
        )
        return result.check() if opts.check else result

    async def spawn(
        self, executable: Any, arguments: Optional[Sequence[Any]] = None, **options: Any
    ) -> AsyncProcess:
        """Start ``executable`` and return an :class:`AsyncProcess` to interact with.

        ::

            async with await runtime.spawn("python", ["-u", "worker.py"]) as proc:
                await proc.write("job-1\\n")
                async for stream, chunk in proc:
                    print(chunk)
        """
        opts = self._defaults.merge(options)
        opts.validate()
        exe, args = _core.normalise(executable, arguments)
        _core.check_cwd(opts.cwd)
        return AsyncProcess(await _start(exe, args, opts, shell=False), exe, args, opts)


class AsyncProcess:
    """A running process you can talk to from coroutines.

    Output arrives through :attr:`output` as ``(Stream, bytes)`` pairs. The queue behind
    it is bounded: stop consuming and Kryon stops reading, so the child blocks rather
    than your heap growing.

    Construct via :meth:`AsyncRuntime.spawn`, not directly.
    """

    def __init__(
        self,
        proc: asyncio.subprocess.Process,
        executable: str,
        arguments: Sequence[str],
        options: ExecutionOptions,
    ) -> None:
        self._proc = proc
        self._executable = executable
        self._arguments = tuple(arguments)
        self._options = options
        self._started = time.monotonic()
        self._reason = Reason()
        self._queue: asyncio.Queue[Any] = asyncio.Queue(maxsize=QUEUE_DEPTH)
        self._consumed = False
        self._bytes_seen = 0
        self._closed = False
        self._readers = [
            asyncio.ensure_future(self._read(Stream.STDOUT, proc.stdout)),
            asyncio.ensure_future(self._read(Stream.STDERR, proc.stderr)),
        ]
        self._deadline: "Optional[asyncio.Task[None]]" = None
        if options.timeout is not None:
            self._deadline = asyncio.ensure_future(self._enforce_timeout(options.timeout))

    @property
    def pid(self) -> int:
        """The operating-system process id."""
        return self._proc.pid

    @property
    def running(self) -> bool:
        """Whether the process is still alive."""
        return self._proc.returncode is None

    @property
    def exit_code(self) -> Optional[int]:
        """The exit status once the process has been reaped, otherwise ``None``."""
        return self._proc.returncode

    def __repr__(self) -> str:  # pragma: no cover - cosmetic
        state = "running" if self.running else f"exited {self.exit_code}"
        return f"AsyncProcess(pid={self.pid}, {self._executable!r}, {state})"

    async def write(self, data: Any) -> None:
        """Write to the child's stdin, respecting the transport's flow control."""
        if self._proc.stdin is None or self._proc.stdin.is_closing():
            raise ValueError(f"stdin of pid {self.pid} is closed")
        if isinstance(data, str):
            data = data.encode(self._options.encoding or "utf-8")
        self._proc.stdin.write(data)
        with contextlib.suppress(ConnectionResetError, BrokenPipeError):
            await self._proc.stdin.drain()

    def close_stdin(self) -> None:
        """Close stdin, signalling end-of-input to the child."""
        if self._proc.stdin is not None and not self._proc.stdin.is_closing():
            with contextlib.suppress(OSError, ConnectionResetError):
                self._proc.stdin.close()

    @property
    def output(self) -> AsyncIterator[Tuple[Stream, bytes]]:
        """Iterate ``(stream, chunk)`` pairs until both pipes reach end-of-input."""
        if self._consumed:
            raise ValueError(
                f"output of pid {self.pid} is already being consumed; a process has one "
                "output stream, and two readers would each get an arbitrary half of it"
            )
        self._consumed = True
        return self._iterate()

    def __aiter__(self) -> AsyncIterator[Tuple[Stream, bytes]]:
        return self.output

    async def _iterate(self) -> AsyncIterator[Tuple[Stream, bytes]]:
        open_streams = 2
        while open_streams:
            item = await self._queue.get()
            if item is _EOF:
                open_streams -= 1
                continue
            yield item

    async def _read(self, stream: Stream, reader: Optional[asyncio.StreamReader]) -> None:
        try:
            if reader is None:
                return
            while True:
                data = await reader.read(CHUNK)
                if not data:
                    return
                self._bytes_seen += len(data)
                limit = self._options.max_output_bytes
                if (
                    limit is not None
                    and self._bytes_seen > limit
                    and self._reason.set(TerminationReason.OUTPUT_LIMIT)
                ):
                    await _stop(self._proc, self._options.kill_grace)
                await self._queue.put((stream, data))
        except (asyncio.CancelledError, ConnectionResetError, BrokenPipeError):
            return
        finally:
            with contextlib.suppress(asyncio.CancelledError):
                await self._queue.put(_EOF)

    def signal(self, number: int) -> None:
        """Send a specific signal.

        :raises UnsupportedPlatform: on Windows, which has no signals to send.
        """
        if _core.WINDOWS:
            raise UnsupportedPlatform(
                "Windows has no signals; use terminate(), which kills the process "
                "outright without letting it clean up"
            )
        self._proc.send_signal(number)

    def terminate(self) -> None:
        """Request a polite stop. On Windows this is identical to :meth:`kill`."""
        with contextlib.suppress(ProcessLookupError, OSError):
            self._proc.terminate()

    def kill(self) -> None:
        """Force a stop."""
        with contextlib.suppress(ProcessLookupError, OSError):
            self._proc.kill()

    async def wait(self, timeout: Optional[float] = None) -> ExecutionResult:
        """Wait for exit and return the outcome.

        ``stdout`` and ``stderr`` on the result are empty: the output was streamed to you
        and is deliberately not buffered a second time.

        :raises ProcessTimeout: ``timeout`` elapsed. The process is left running.
        """
        try:
            await asyncio.wait_for(self._proc.wait(), timeout=timeout)
        except asyncio.TimeoutError:
            raise ProcessTimeout(
                f"{self._executable!r} (pid {self.pid}) still running after {timeout}s"
            ) from None
        termination, exit_code, sig = _core.classify(self._proc.returncode, self._reason.value)
        empty = "" if self._options.encoding else b""
        return ExecutionResult(
            executable=self._executable,
            arguments=self._arguments,
            exit_code=exit_code,
            signal=sig,
            stdout=empty,
            stderr=empty,
            duration=time.monotonic() - self._started,
            termination=termination,
            pid=self._proc.pid,
        )

    async def close(self) -> None:
        """Terminate if still running and release every resource. Idempotent."""
        if self._closed:
            return
        self._closed = True
        if self._deadline is not None:
            self._deadline.cancel()
        self.close_stdin()
        if self.running:
            self._reason.set(TerminationReason.CANCELLED)
            await _stop(self._proc, self._options.kill_grace)
        for reader in self._readers:
            reader.cancel()
        await asyncio.gather(*self._readers, self._deadline or _noop(), return_exceptions=True)
        with contextlib.suppress(asyncio.TimeoutError):
            await asyncio.wait_for(self._proc.wait(), timeout=1.0)

    async def _enforce_timeout(self, timeout: float) -> None:
        with contextlib.suppress(asyncio.CancelledError):
            await asyncio.sleep(timeout)
            if self.running and self._reason.set(TerminationReason.TIMEOUT):
                await _stop(self._proc, self._options.kill_grace)

    async def __aenter__(self) -> AsyncProcess:
        return self

    async def __aexit__(
        self,
        exc_type: Optional[Type[BaseException]],
        exc: Optional[BaseException],
        tb: Optional[TracebackType],
    ) -> None:
        await self.close()


# -- module-private helpers ------------------------------------------------


async def _start(
    executable: str, arguments: Sequence[str], opts: ExecutionOptions, shell: bool
) -> asyncio.subprocess.Process:
    pipe = asyncio.subprocess.PIPE
    env = _core.build_env(opts)
    try:
        if shell:
            return await asyncio.create_subprocess_shell(
                executable, stdin=pipe, stdout=pipe, stderr=pipe, cwd=opts.cwd, env=env
            )
        return await asyncio.create_subprocess_exec(
            executable, *arguments, stdin=pipe, stdout=pipe, stderr=pipe, cwd=opts.cwd, env=env
        )
    except OSError as exc:
        raise _core.map_start_error(exc, executable, opts.cwd) from exc


async def _drain(
    reader: Optional[asyncio.StreamReader],
    buffer: Buffer,
    reason: Reason,
    proc: asyncio.subprocess.Process,
    opts: ExecutionOptions,
) -> None:
    if reader is None:
        return
    try:
        while True:
            data = await reader.read(CHUNK)
            if not data:
                return
            if buffer.add(data) and reason.set(TerminationReason.OUTPUT_LIMIT):
                await _stop(proc, opts.kill_grace)
    except (asyncio.CancelledError, ConnectionResetError, BrokenPipeError):
        return


async def _feed(writer: Optional[asyncio.StreamWriter], data: bytes) -> None:
    if writer is None:
        return
    try:
        if data:
            writer.write(data)
            await writer.drain()
    except (ConnectionResetError, BrokenPipeError, OSError):
        pass  # child exited before reading its input; its exit code is the story
    finally:
        with contextlib.suppress(OSError, ConnectionResetError, BrokenPipeError):
            writer.close()


async def _stop(proc: asyncio.subprocess.Process, grace: float) -> None:
    """Terminate politely, then kill. The single termination path in the async runtime."""
    if proc.returncode is not None:
        return
    try:
        proc.terminate()
    except (ProcessLookupError, OSError):
        return
    try:
        await asyncio.wait_for(asyncio.shield(proc.wait()), timeout=grace)
        return
    except (asyncio.TimeoutError, asyncio.CancelledError):
        pass
    with contextlib.suppress(ProcessLookupError, OSError):
        proc.kill()
    # Reaping matters even while unwinding: an unawaited child becomes a zombie.
    with contextlib.suppress(asyncio.TimeoutError, asyncio.CancelledError):
        await asyncio.wait_for(asyncio.shield(proc.wait()), timeout=grace)


async def _noop() -> None:
    return None


def _encode_stdin(opts: ExecutionOptions) -> bytes:
    data = opts.stdin
    if data is None:
        return b""
    if isinstance(data, str):
        return data.encode(opts.encoding or "utf-8")
    return bytes(data)
