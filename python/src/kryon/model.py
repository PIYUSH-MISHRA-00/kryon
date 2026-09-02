"""Value types shared by the sync and async runtimes.

These are the Python spelling of the conceptual objects in ``spec/execution.md``.
They hold no resources and perform no I/O, which is what makes them safe to pass
around, log, compare and pickle.
"""

from __future__ import annotations

import dataclasses
from collections.abc import Mapping, Sequence
from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Optional, Union

from kryon.errors import (
    InvalidArguments,
    ProcessCancelled,
    ProcessFailed,
    ProcessTimeout,
    ResourceLimitExceeded,
)

__all__ = ["ExecutionOptions", "ExecutionResult", "Stream", "TerminationReason"]

#: What ``stdout``/``stderr`` hold: bytes by default, text when ``encoding`` is set.
Output = Union[bytes, str]


class Stream(str, Enum):
    """Which pipe a chunk of output arrived on."""

    STDOUT = "stdout"
    STDERR = "stderr"


class TerminationReason(str, Enum):
    """Why the process stopped.

    The three Kryon-initiated reasons take precedence over the operating system's own
    account of the death. A process killed because it exceeded its timeout was, at the
    kernel level, ``SIGNALED`` -- but ``TIMEOUT`` is the fact the caller needs in order
    to react correctly, so that is what is reported.
    """

    EXITED = "EXITED"
    """The process exited on its own. ``exit_code`` is present."""

    SIGNALED = "SIGNALED"
    """The process was killed by a signal Kryon did not send. POSIX only."""

    TIMEOUT = "TIMEOUT"
    """``timeout`` elapsed and Kryon terminated the process."""

    CANCELLED = "CANCELLED"
    """The caller cancelled and Kryon terminated the process."""

    OUTPUT_LIMIT = "OUTPUT_LIMIT"
    """``max_output_bytes`` was exceeded and Kryon stopped the process."""


@dataclass(frozen=True)
class ExecutionOptions:
    """Resolved options for a single execution.

    You rarely construct this yourself -- pass the fields as keyword arguments to
    :meth:`~kryon.Runtime.execute` instead. It exists so that a :class:`~kryon.Runtime`
    can carry defaults and each call can override them.
    """

    cwd: Optional[str] = None
    """Working directory. Inherited when unset. A path that is not a directory is an
    error, never a silent fallback to the current directory."""

    env: Mapping[str, Optional[str]] = field(default_factory=dict)
    """Variables merged *over* the inherited environment. A ``None`` value removes the
    variable. To control the environment strictly, combine with ``clear_env``."""

    clear_env: bool = False
    """Start from an empty environment instead of inheriting one. With ``env``, this is
    an allowlist. On Windows, ``SystemRoot`` and ``SystemDrive`` are still preserved,
    because many binaries fail to start without them."""

    stdin: Optional[Union[bytes, str]] = None
    """Data written to the child's stdin, after which stdin is closed. Text is encoded
    with ``encoding``, or UTF-8 when no encoding is set."""

    timeout: Optional[float] = None
    """Wall-clock limit in seconds. On expiry the process is terminated politely, then
    killed after ``kill_grace``. Output collected so far is kept."""

    max_output_bytes: Optional[int] = None
    """Per-stream cap in bytes, counted before decoding. Exceeding it stops the process
    and sets the matching ``*_truncated`` flag."""

    encoding: Optional[str] = None
    """When set, output is decoded with this codec and ``errors='replace'``; when unset,
    output is bytes. ``'replace'`` is not negotiable here: an output cap can cut a
    multi-byte character in half, and raising on that would turn a truncation into a
    crash."""

    check: bool = False
    """Raise instead of returning when the result is not successful. Never converts a
    failure-to-start into a result."""

    kill_grace: float = 5.0
    """Seconds between the polite stop and the forced kill."""

    def merge(self, overrides: Mapping[str, Any]) -> ExecutionOptions:
        """Return a copy with ``overrides`` applied. ``env`` merges rather than replaces."""
        unknown = set(overrides) - _OPTION_NAMES
        if unknown:
            raise InvalidArguments(
                f"unknown option(s): {', '.join(sorted(unknown))}; "
                f"valid options are {', '.join(sorted(_OPTION_NAMES))}"
            )
        if not overrides:
            return self
        overrides = dict(overrides)
        if "env" in overrides and self.env:
            overrides["env"] = {**self.env, **(overrides["env"] or {})}
        return dataclasses.replace(self, **overrides)

    def validate(self) -> None:
        """Reject a malformed request before anything is spawned."""
        if self.timeout is not None and self.timeout <= 0:
            raise InvalidArguments(f"timeout must be positive, got {self.timeout!r}")
        if self.max_output_bytes is not None and self.max_output_bytes <= 0:
            raise InvalidArguments(
                f"max_output_bytes must be positive, got {self.max_output_bytes!r}"
            )
        if self.kill_grace < 0:
            raise InvalidArguments(f"kill_grace must not be negative, got {self.kill_grace!r}")


_OPTION_NAMES = frozenset(f.name for f in dataclasses.fields(ExecutionOptions))


@dataclass(frozen=True)
class ExecutionResult:
    """What happened when a process ran.

    A result exists only for a process that actually started. Failures to start raise;
    see :mod:`kryon.errors`.
    """

    executable: str
    arguments: Sequence[str]
    exit_code: Optional[int]
    signal: Optional[int]
    stdout: Output
    stderr: Output
    duration: float
    termination: TerminationReason
    pid: Optional[int] = None
    stdout_truncated: bool = False
    stderr_truncated: bool = False

    @property
    def ok(self) -> bool:
        """``True`` only for a process that exited on its own with status ``0``."""
        return self.termination is TerminationReason.EXITED and self.exit_code == 0

    def check(self) -> ExecutionResult:
        """Return ``self`` if successful, otherwise raise the matching error.

        This is what ``check=True`` calls. Useful on its own when you want to inspect a
        result first and only then insist it succeeded.
        """
        if self.ok:
            return self
        detail = _detail(self)
        if self.termination is TerminationReason.TIMEOUT:
            raise ProcessTimeout(f"{self.executable!r} timed out{detail}", self)
        if self.termination is TerminationReason.CANCELLED:
            raise ProcessCancelled(f"{self.executable!r} was cancelled{detail}", self)
        if self.termination is TerminationReason.OUTPUT_LIMIT:
            raise ResourceLimitExceeded(
                f"{self.executable!r} exceeded its output limit{detail}", self
            )
        if self.termination is TerminationReason.SIGNALED:
            raise ProcessFailed(
                f"{self.executable!r} was killed by signal {self.signal}{detail}", self
            )
        raise ProcessFailed(f"{self.executable!r} exited with code {self.exit_code}{detail}", self)

    def __repr__(self) -> str:  # pragma: no cover - cosmetic
        return (
            f"ExecutionResult(executable={self.executable!r}, "
            f"exit_code={self.exit_code!r}, termination={self.termination.value}, "
            f"duration={self.duration:.3f}s, "
            f"stdout={_size(self.stdout)}, stderr={_size(self.stderr)})"
        )


def _size(data: Output) -> str:
    unit = "chars" if isinstance(data, str) else "bytes"
    return f"{len(data)} {unit}"


def _detail(result: ExecutionResult) -> str:
    """A short stderr excerpt for the error message.

    Deliberately capped and deliberately stderr-only: environments and stdin routinely
    hold credentials, and an error message is the most-pasted string a library produces.
    """
    text = result.stderr
    if isinstance(text, bytes):
        text = text.decode("utf-8", "replace")
    text = text.strip()
    if not text:
        return ""
    if len(text) > 500:
        text = text[:500] + "..."
    return f"\nstderr: {text}"
