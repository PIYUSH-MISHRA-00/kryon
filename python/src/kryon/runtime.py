"""The synchronous Kryon runtime.

Two operations, deliberately separate:

* :meth:`Runtime.execute` runs a program to completion and hands back everything it
  produced. Use it when you want the answer.
* :meth:`Runtime.spawn` starts a program and hands back a :class:`Process` you can write
  to, read from and signal while it runs. Use it when you want a conversation.

For ``async``/``await``, see :mod:`kryon.aio`.
"""

from __future__ import annotations

import contextlib
import queue
import subprocess
import threading
import time
from collections.abc import Iterator, Mapping, Sequence
from types import TracebackType
from typing import Any, Callable, Optional, Tuple, Type

from kryon import _core
from kryon._core import CHUNK, QUEUE_DEPTH, Buffer, Reason
from kryon.errors import InvalidArguments, UnsupportedPlatform
from kryon.model import ExecutionOptions, ExecutionResult, Stream, TerminationReason

__all__ = ["Process", "Runtime"]

_EOF = object()


class Runtime:
    """An execution context holding default options.

    A ``Runtime`` holds configuration, not state. It is safe to share across threads, and
    creating one is cheap enough that you can also just make a new one::

        runtime = Runtime(timeout=30, encoding="utf-8", cwd="/srv/app")
        result = runtime.execute("git", ["status", "--porcelain"])

    Any default can be overridden per call. ``env`` merges with the runtime's ``env``
    rather than replacing it; every other option is replaced.
    """

    def __init__(self, **defaults: Any) -> None:
        self._defaults = ExecutionOptions().merge(defaults)
        self._defaults.validate()

    @property
    def defaults(self) -> ExecutionOptions:
        """The options this runtime applies when a call does not override them."""
        return self._defaults

    def __repr__(self) -> str:  # pragma: no cover - cosmetic
        return f"Runtime(defaults={self._defaults!r})"

    # -- one-shot execution ------------------------------------------------

    def execute(
        self, executable: Any, arguments: Optional[Sequence[Any]] = None, **options: Any
    ) -> ExecutionResult:
        """Run ``executable`` with ``arguments`` and return what happened.

        Arguments are passed to the operating system as a vector. Nothing in them is
        interpreted -- ``execute("echo", ["$HOME && rm -rf /"])`` prints that text
        literally. For shell semantics you must ask for them by name; see
        :meth:`execute_shell`.

        A process that could not be started raises. A process that started and then
        failed is returned, because a non-zero exit is information: ``grep`` exits ``1``
        to mean "no match". Pass ``check=True`` to raise on those too.

        :raises CommandNotFound: the executable could not be resolved
        :raises PermissionDenied: the executable could not be run
        :raises ProcessStartFailed: the process could not be created
        :raises InvalidArguments: the request was malformed
        """
        return self._execute(executable, arguments, options, shell=False)

    def execute_shell(self, command_line: str, **options: Any) -> ExecutionResult:
        """Run ``command_line`` through the system shell.

        .. warning::
           The shell interprets quoting, globbing, variable expansion, pipes and command
           chaining. Building this string from untrusted input is a command-injection
           vulnerability. If you are interpolating a value, you almost certainly want
           :meth:`execute` with an argument list instead.

        The shell is ``/bin/sh -c`` on POSIX and ``%COMSPEC% /c`` on Windows.

        This method exists as a separate name on purpose. A ``shell=True`` flag sitting
        among a dozen options is easy to set by accident and easy to miss in review; a
        differently named method is not.
        """
        if not isinstance(command_line, str):
            raise InvalidArguments(
                f"command_line must be a string, got {type(command_line).__name__}"
            )
        return self._execute(command_line, None, options, shell=True)

    def _execute(
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
        proc = _start(exe, args, opts, shell)
        reason = Reason()
        out = Buffer(opts.max_output_bytes)
        err = Buffer(opts.max_output_bytes)

        workers = [
            _thread(self._pump, proc.stdout, out, reason, proc, opts),
            _thread(self._pump, proc.stderr, err, reason, proc, opts),
            _thread(_feed, proc.stdin, _encode_stdin(opts)),
        ]
        try:
            proc.wait(timeout=opts.timeout)
        except subprocess.TimeoutExpired:
            reason.set(TerminationReason.TIMEOUT)
            _core.stop(proc, opts.kill_grace)
            proc.wait()
        except BaseException:
            # KeyboardInterrupt, or anything else unwinding the stack. Returning control
            # to the caller while leaving the child running is not an option.
            reason.set(TerminationReason.CANCELLED)
            _core.stop(proc, opts.kill_grace)
            raise
        finally:
            # A grandchild can hold the pipes open after the child exits, so this join is
            # bounded. Without the bound, `sh -c "sleep 100 &"` would hang the caller.
            for worker in workers:
                worker.join(timeout=max(opts.kill_grace, 1.0))
            _close(proc)

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

    @staticmethod
    def _pump(
        fh: Any,
        buffer: Buffer,
        reason: Reason,
        proc: "subprocess.Popen[bytes]",
        opts: ExecutionOptions,
    ) -> None:
        try:
            while True:
                data = fh.read1(CHUNK)
                if not data:
                    return
                if buffer.add(data) and reason.set(TerminationReason.OUTPUT_LIMIT):
                    _core.stop(proc, opts.kill_grace)
        except (OSError, ValueError):
            return  # pipe closed underneath us during shutdown

    # -- long-lived processes ----------------------------------------------

    def spawn(
        self, executable: Any, arguments: Optional[Sequence[Any]] = None, **options: Any
    ) -> Process:
        """Start ``executable`` and return a :class:`Process` to interact with.

        Returns as soon as the process has started. Use it as a context manager so the
        process cannot outlive the block::

            with runtime.spawn("python", ["-u", "worker.py"]) as proc:
                proc.write("job-1\\n")
                for stream, chunk in proc:
                    print(chunk)
        """
        opts = self._defaults.merge(options)
        opts.validate()
        exe, args = _core.normalise(executable, arguments)
        _core.check_cwd(opts.cwd)
        return Process(_start(exe, args, opts, shell=False), exe, args, opts)


class Process:
    """A running process you can talk to.

    Output arrives through :attr:`output` as ``(Stream, bytes)`` pairs in the order
    Kryon observed them. Chunk boundaries mean nothing -- they reflect how the operating
    system delivered the data, not lines or records.

    The stream is bounded. If you stop consuming, Kryon stops reading, the pipe fills and
    the child blocks. That is backpressure working, not a hang: a program that produces
    faster than you consume must be slowed down somewhere, and doing it in the kernel
    buffer is better than doing it in your heap.

    Construct via :meth:`Runtime.spawn`, not directly.
    """

    def __init__(
        self,
        proc: subprocess.Popen[bytes],
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
        self._queue: queue.Queue[Any] = queue.Queue(maxsize=QUEUE_DEPTH)
        self._consumed = False
        self._bytes_seen = 0
        self._closed = False
        self._readers = [
            _thread(self._read, Stream.STDOUT, proc.stdout),
            _thread(self._read, Stream.STDERR, proc.stderr),
        ]
        self._timer: Optional[threading.Timer] = None
        if options.timeout is not None:
            self._timer = threading.Timer(options.timeout, self._on_timeout)
            self._timer.daemon = True
            self._timer.start()

    # -- identity ----------------------------------------------------------

    @property
    def pid(self) -> int:
        """The operating-system process id."""
        return self._proc.pid

    @property
    def running(self) -> bool:
        """Whether the process is still alive."""
        return self._proc.poll() is None

    @property
    def exit_code(self) -> Optional[int]:
        """The exit status once the process has been reaped, otherwise ``None``."""
        return self._proc.poll()

    def __repr__(self) -> str:  # pragma: no cover - cosmetic
        state = "running" if self.running else f"exited {self.exit_code}"
        return f"Process(pid={self.pid}, {self._executable!r}, {state})"

    # -- input -------------------------------------------------------------

    def write(self, data: Any) -> None:
        """Write to the child's stdin.

        Text is encoded with the runtime's ``encoding``, or UTF-8 when none is set.
        Raises if stdin is already closed -- dropping input silently is the failure mode
        that produces hangs nobody can reproduce.
        """
        if self._proc.stdin is None or self._proc.stdin.closed:
            raise ValueError(f"stdin of pid {self.pid} is closed")
        if isinstance(data, str):
            data = data.encode(self._options.encoding or "utf-8")
        try:
            self._proc.stdin.write(data)
            self._proc.stdin.flush()
        except BrokenPipeError:
            # The child stopped reading. Its exit code, not this write, is the story.
            pass

    def close_stdin(self) -> None:
        """Close stdin, signalling end-of-input to the child."""
        if self._proc.stdin is not None and not self._proc.stdin.closed:
            with contextlib.suppress(OSError):
                self._proc.stdin.close()

    # -- output ------------------------------------------------------------

    @property
    def output(self) -> Iterator[Tuple[Stream, bytes]]:
        """Iterate ``(stream, chunk)`` pairs until both pipes reach end-of-input.

        There is one consumer. Iterating twice raises, because the second iterator would
        silently steal chunks from the first.
        """
        if self._consumed:
            raise ValueError(
                f"output of pid {self.pid} is already being consumed; a process has one "
                "output stream, and two readers would each get an arbitrary half of it"
            )
        self._consumed = True
        return self._iterate()

    def __iter__(self) -> Iterator[Tuple[Stream, bytes]]:
        return self.output

    def _iterate(self) -> Iterator[Tuple[Stream, bytes]]:
        open_streams = 2
        while open_streams:
            item = self._queue.get()
            if item is _EOF:
                open_streams -= 1
                continue
            yield item

    def _read(self, stream: Stream, fh: Any) -> None:
        try:
            while True:
                data = fh.read1(CHUNK)
                if not data:
                    return
                self._bytes_seen += len(data)
                limit = self._options.max_output_bytes
                if (
                    limit is not None
                    and self._bytes_seen > limit
                    and self._reason.set(TerminationReason.OUTPUT_LIMIT)
                ):
                    _core.stop(self._proc, self._options.kill_grace)
                self._queue.put((stream, data))
        except (OSError, ValueError):
            return
        finally:
            self._queue.put(_EOF)

    # -- lifecycle ---------------------------------------------------------

    def signal(self, number: int) -> None:
        """Send a specific signal to the process.

        :raises UnsupportedPlatform: on Windows, which has no signals to send. Use
            :meth:`terminate` there, and know that it does not give the child a chance
            to clean up.
        """
        if _core.WINDOWS:
            raise UnsupportedPlatform(
                "Windows has no signals; use terminate(), which kills the process "
                "outright without letting it clean up"
            )
        self._proc.send_signal(number)

    def terminate(self) -> None:
        """Request a polite stop: ``SIGTERM`` on POSIX, ``TerminateProcess`` on Windows.

        On Windows this is identical to :meth:`kill`. There is no graceful stop.
        """
        with contextlib.suppress(OSError):
            self._proc.terminate()

    def kill(self) -> None:
        """Force a stop: ``SIGKILL`` on POSIX, ``TerminateProcess`` on Windows."""
        with contextlib.suppress(OSError):
            self._proc.kill()

    def wait(self, timeout: Optional[float] = None) -> ExecutionResult:
        """Block until the process exits and return the outcome.

        ``stdout`` and ``stderr`` on the result are empty: the output was streamed to
        you through :attr:`output` and is deliberately not buffered a second time.

        :raises ProcessTimeout: ``timeout`` elapsed. The process is left running -- this
            is a wait, not a stop. Call :meth:`terminate` or :meth:`close` for that.
        """
        try:
            self._proc.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            from kryon.errors import ProcessTimeout

            raise ProcessTimeout(
                f"{self._executable!r} (pid {self.pid}) still running after {timeout}s"
            ) from None
        if self._timer is not None:
            self._timer.cancel()
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

    def close(self) -> None:
        """Terminate the process if it is still running and release every resource.

        Idempotent. This is what leaving a ``with`` block does.
        """
        if self._closed:
            return
        self._closed = True
        if self._timer is not None:
            self._timer.cancel()
        self.close_stdin()
        if self.running:
            self._reason.set(TerminationReason.CANCELLED)
            _core.stop(self._proc, self._options.kill_grace)
        # A reader blocked on a full queue will never see the pipe close, so drain until
        # the threads finish. Without this, close() on an unconsumed process leaks two
        # threads and two file descriptors.
        deadline = time.monotonic() + max(self._options.kill_grace, 1.0)
        while any(t.is_alive() for t in self._readers) and time.monotonic() < deadline:
            with contextlib.suppress(queue.Empty):
                self._queue.get(timeout=0.05)
        for reader in self._readers:
            reader.join(timeout=0.5)
        _close(self._proc)
        with contextlib.suppress(subprocess.TimeoutExpired):  # pragma: no cover
            self._proc.wait(timeout=1.0)

    def _on_timeout(self) -> None:
        if self.running and self._reason.set(TerminationReason.TIMEOUT):
            _core.stop(self._proc, self._options.kill_grace)

    def __enter__(self) -> Process:
        return self

    def __exit__(
        self,
        exc_type: Optional[Type[BaseException]],
        exc: Optional[BaseException],
        tb: Optional[TracebackType],
    ) -> None:
        self.close()

    def __del__(self) -> None:  # pragma: no cover - interpreter shutdown ordering
        if not self._closed and self._proc.poll() is None:
            import warnings

            warnings.warn(
                f"Process(pid={self._proc.pid}, {self._executable!r}) was garbage "
                "collected while still running; use it as a context manager or call "
                "close()",
                ResourceWarning,
                stacklevel=2,
            )
            self.close()


# -- module-private helpers ------------------------------------------------


def _start(
    executable: str, arguments: Sequence[str], opts: ExecutionOptions, shell: bool
) -> subprocess.Popen[bytes]:
    try:
        return subprocess.Popen(
            _core.popen_args(executable, arguments, shell),
            shell=shell,
            cwd=opts.cwd,
            env=_core.build_env(opts),
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
    except OSError as exc:
        raise _core.map_start_error(exc, executable, opts.cwd) from exc


def _encode_stdin(opts: ExecutionOptions) -> bytes:
    data = opts.stdin
    if data is None:
        return b""
    if isinstance(data, str):
        return data.encode(opts.encoding or "utf-8")
    return bytes(data)


def _feed(fh: Any, data: bytes) -> None:
    try:
        if data:
            fh.write(data)
            fh.flush()
    except (OSError, ValueError):
        pass  # child exited before reading its input; its exit code is the story
    finally:
        with contextlib.suppress(OSError, ValueError):
            fh.close()


def _thread(target: Callable[..., None], *args: Any) -> threading.Thread:
    thread = threading.Thread(target=target, args=args, daemon=True)
    thread.start()
    return thread


def _close(proc: subprocess.Popen[bytes]) -> None:
    for fh in (proc.stdin, proc.stdout, proc.stderr):
        if fh is not None and not fh.closed:
            with contextlib.suppress(OSError, ValueError):
                fh.close()
