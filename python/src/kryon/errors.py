"""Kryon's error taxonomy.

The organising rule, from ``spec/errors.md``:

    Failing to start is an error. Failing while running is a result.

A command that could not be found never ran, so there is no result to return and
:class:`CommandNotFound` is raised. A command that ran and exited ``1`` did run --
``grep`` exits ``1`` to mean "no match" -- so that is reported in
:class:`~kryon.model.ExecutionResult`, not raised. Callers who want the strict style
pass ``check=True``, which turns unsuccessful results into the errors below.

Kryon errors also inherit from the closest builtin so that code written before Kryon
still catches them: ``except FileNotFoundError`` catches :class:`CommandNotFound`, and
``except TimeoutError`` catches :class:`ProcessTimeout`.
"""

from __future__ import annotations

from typing import TYPE_CHECKING, Optional

if TYPE_CHECKING:  # pragma: no cover - typing only
    from kryon.model import ExecutionResult

__all__ = [
    "CommandNotFound",
    "InvalidArguments",
    "KryonError",
    "PermissionDenied",
    "ProcessCancelled",
    "ProcessFailed",
    "ProcessStartFailed",
    "ProcessTimeout",
    "ResourceLimitExceeded",
    "UnsupportedPlatform",
]


class KryonError(Exception):
    """Base class for everything Kryon raises.

    Catch this to catch Kryon and nothing else.
    """


class InvalidArguments(KryonError, ValueError):
    """The request was malformed before any process was created.

    Raised for an empty executable, a negative timeout, a non-string argument -- the
    things that are cheaper to reject than to clean up after.
    """


class CommandNotFound(KryonError, FileNotFoundError):
    """The executable could not be resolved on ``PATH`` or at the given path."""


class PermissionDenied(KryonError, PermissionError):
    """The executable exists but could not be executed, or ``cwd`` could not be entered."""


class ProcessStartFailed(KryonError, OSError):
    """The process could not be created, for a reason other than the two above.

    A missing working directory, a resource limit, a platform refusal. The underlying
    exception is chained with ``raise ... from``.
    """


class _ResultError(KryonError):
    """Base for errors that describe a process which really ran.

    Every one of these carries the :class:`~kryon.model.ExecutionResult` it came from.
    An error that discards the stderr explaining what went wrong is a worse error than
    no error at all.
    """

    def __init__(self, message: str, result: Optional[ExecutionResult] = None) -> None:
        super().__init__(message)
        self.result = result


class ProcessFailed(_ResultError):
    """The process exited with a non-zero status, and ``check`` was set."""


class ProcessTimeout(_ResultError, TimeoutError):
    """``timeout`` elapsed and Kryon terminated the process.

    ``result`` holds the output collected before termination; it is not discarded.
    """


class ProcessCancelled(_ResultError):
    """The caller cancelled the operation and Kryon terminated the process."""


class ResourceLimitExceeded(_ResultError):
    """An output limit was exceeded and Kryon stopped the process.

    Note that this is a memory-management mechanism, not a security boundary. See
    ``docs/security/threat-model.md``.
    """


class UnsupportedPlatform(KryonError, NotImplementedError):
    """The operation cannot exist on this platform.

    Distinct from a transient failure: this means *never here*, not *not right now*.
    ``Process.signal()`` on Windows is the current example -- Windows has no signals to
    send.
    """
