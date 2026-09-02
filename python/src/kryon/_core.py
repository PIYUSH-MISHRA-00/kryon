"""Plumbing shared by the sync and async runtimes.

Nothing here is public API. It exists so that :mod:`kryon.runtime` and :mod:`kryon.aio`
cannot drift apart on the details that matter -- how the environment is built, how a
start failure is classified, how a process is stopped.
"""

from __future__ import annotations

import contextlib
import os
import subprocess
import threading
from collections.abc import Mapping, Sequence
from typing import Any, List, Optional, Tuple, Union

from kryon.errors import (
    CommandNotFound,
    InvalidArguments,
    PermissionDenied,
    ProcessStartFailed,
)
from kryon.model import ExecutionOptions, Output, TerminationReason

WINDOWS = os.name == "nt"

#: Read size for output pumps. Large enough that a chatty process does not cost a
#: syscall per line, small enough that an interactive prompt is not held back.
CHUNK = 65536

#: Bounded queue depth for streaming output. This is the backpressure knob: once the
#: consumer is this far behind, Kryon stops reading, the OS pipe fills, and the child
#: blocks. Bounded memory beats a fast producer every time.
QUEUE_DEPTH = 64

#: Preserved even when ``clear_env`` is set. Windows binaries -- including the ones in
#: ``System32`` -- routinely fail to start without these.
_WINDOWS_ESSENTIAL = ("SystemRoot", "SystemDrive")


def normalise(executable: Any, arguments: Optional[Sequence[Any]]) -> Tuple[str, List[str]]:
    """Validate and coerce the command, or raise :class:`InvalidArguments`."""
    if executable is None:
        raise InvalidArguments("executable must not be None")
    if isinstance(executable, (list, tuple)):
        raise InvalidArguments(
            "executable must be a single program; pass its arguments as the second "
            f"parameter: execute({executable[0]!r}, {list(executable[1:])!r})"
        )
    try:
        exe = os.fspath(executable)
    except TypeError:
        raise InvalidArguments(
            f"executable must be a string or path, got {type(executable).__name__}"
        ) from None
    if isinstance(exe, bytes):
        exe = os.fsdecode(exe)
    if not exe:
        raise InvalidArguments("executable must not be empty")

    args: List[str] = []
    for index, value in enumerate(arguments or ()):
        try:
            arg = os.fspath(value)
        except TypeError:
            raise InvalidArguments(
                f"argument {index} must be a string or path, got "
                f"{type(value).__name__}; Kryon does not stringify arguments for you, "
                "because guessing how to render an object into a command line is how "
                "injection bugs start"
            ) from None
        args.append(os.fsdecode(arg) if isinstance(arg, bytes) else arg)
    return exe, args


def build_env(options: ExecutionOptions) -> Optional[Mapping[str, str]]:
    """Materialise the child environment, or ``None`` to inherit unchanged."""
    if not options.clear_env and not options.env:
        return None

    if options.clear_env:
        env = {}
        if WINDOWS:
            env = {k: os.environ[k] for k in _WINDOWS_ESSENTIAL if k in os.environ}
    else:
        env = dict(os.environ)

    for key, value in (options.env or {}).items():
        if value is None:
            env.pop(key, None)
        else:
            env[key] = str(value)
    return env


def check_cwd(cwd: Optional[str]) -> None:
    """Reject a bad working directory up front.

    Without this, a missing ``cwd`` surfaces as the same ``FileNotFoundError`` as a
    missing executable, and the caller is told the wrong thing.
    """
    if cwd is None:
        return
    if not os.path.exists(cwd):
        raise ProcessStartFailed(f"working directory does not exist: {cwd!r}")
    if not os.path.isdir(cwd):
        raise ProcessStartFailed(f"working directory is not a directory: {cwd!r}")


def popen_args(executable: str, arguments: Sequence[str], shell: bool) -> Union[str, List[str]]:
    """The ``args`` value to hand to the subprocess layer.

    For ``shell=True`` this is the raw command line: the standard library already knows
    how to hand it to ``/bin/sh -c`` on POSIX and ``%COMSPEC% /c`` on Windows, and its
    platform quoting is better tested than anything written here would be.
    """
    return executable if shell else [executable, *arguments]


def map_start_error(exc: OSError, executable: str, cwd: Optional[str]) -> Exception:
    """Turn a spawn failure into the right Kryon error."""
    if isinstance(exc, PermissionError):
        return PermissionDenied(f"not permitted to execute {executable!r}: {exc}")
    if isinstance(exc, FileNotFoundError):
        return CommandNotFound(
            f"executable not found: {executable!r}"
            + (f" (searched PATH from cwd {cwd!r})" if cwd else "")
        )
    if isinstance(exc, NotADirectoryError):
        return ProcessStartFailed(f"could not start {executable!r}: {exc}")
    return ProcessStartFailed(f"could not start {executable!r}: {exc}")


def classify(
    returncode: Optional[int], reason: Optional[TerminationReason]
) -> Tuple[TerminationReason, Optional[int], Optional[int]]:
    """Map a raw return code plus any Kryon intervention to the reported outcome.

    A negative return code is POSIX's way of reporting a signal. Windows never produces
    one, which is why ``signal`` is absent there.
    """
    if returncode is not None and returncode < 0:
        natural, exit_code, sig = TerminationReason.SIGNALED, None, -returncode
    else:
        natural, exit_code, sig = TerminationReason.EXITED, returncode, None
    return (reason or natural), exit_code, sig


def decode(data: bytes, encoding: Optional[str]) -> Output:
    """Decode captured output, or leave it as bytes.

    ``errors='replace'`` is deliberate: an output cap can slice a multi-byte character
    in half, and a truncation should not become a crash.
    """
    return data.decode(encoding, "replace") if encoding else data


def stop(proc: subprocess.Popen[bytes], grace: float) -> None:
    """Terminate politely, then kill. The single termination path in the sync runtime.

    On Windows both steps are ``TerminateProcess``: there is no graceful stop, and the
    child gets no chance to flush. That difference is real and is documented rather than
    papered over.
    """
    if proc.poll() is not None:
        return
    try:
        proc.terminate()
    except OSError:
        return  # already gone
    try:
        proc.wait(timeout=grace)
        return
    except subprocess.TimeoutExpired:
        pass
    try:
        proc.kill()
    except OSError:
        return
    with contextlib.suppress(subprocess.TimeoutExpired):  # pragma: no cover
        proc.wait(timeout=grace)


class Reason:
    """First-writer-wins holder for why Kryon intervened.

    A timeout, an output cap and a cancellation can race; whichever fires first is the
    reason the caller is told about, and later ones do not overwrite it.
    """

    __slots__ = ("_lock", "_value")

    def __init__(self) -> None:
        self._value: Optional[TerminationReason] = None
        self._lock = threading.Lock()

    def set(self, reason: TerminationReason) -> bool:
        """Record ``reason`` if none is set yet. Returns whether this call won."""
        with self._lock:
            if self._value is None:
                self._value = reason
                return True
            return False

    @property
    def value(self) -> Optional[TerminationReason]:
        return self._value


class Buffer:
    """A byte sink that stops growing at a limit and remembers that it did."""

    __slots__ = ("_parts", "_size", "limit", "truncated")

    def __init__(self, limit: Optional[int]) -> None:
        self.limit = limit
        self._parts: List[bytes] = []
        self._size = 0
        self.truncated = False

    def add(self, data: bytes) -> bool:
        """Append ``data``. Returns ``True`` when the limit has just been exceeded.

        Data past the limit is dropped rather than counted: the point of a cap is to not
        hold the bytes, so reporting an exact total would defeat it.
        """
        if self.limit is None:
            self._parts.append(data)
            self._size += len(data)
            return False
        room = self.limit - self._size
        if room > 0:
            self._parts.append(data[:room])
            self._size += min(room, len(data))
        if len(data) > room:
            self.truncated = True
            return True
        return False

    def value(self) -> bytes:
        return b"".join(self._parts)
