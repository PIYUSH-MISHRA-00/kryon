"""Kryon -- powerful terminal execution, everywhere.

Kryon runs operating-system commands and manages the processes behind them, with one
conceptual API that is being implemented across Python, TypeScript, Dart, Java and
Kotlin. This package is the Python SDK.

Two operations::

    from kryon import Runtime

    runtime = Runtime(encoding="utf-8", timeout=30)

    # Run it and tell me what happened.
    result = runtime.execute("git", ["status", "--porcelain"])
    print(result.stdout, result.exit_code, result.ok)

    # Start it and let me talk to it.
    with runtime.spawn("python", ["-u", "-i"]) as proc:
        proc.write("print(2 ** 10)\\n")
        for stream, chunk in proc:
            print(chunk.decode())

The async twin lives in :mod:`kryon.aio` and has identical semantics.

Two things worth knowing before you use this in anger:

* **Arguments are not interpreted.** ``execute("echo", ["$HOME"])`` prints ``$HOME``.
  Shell semantics require the separately named :meth:`~kryon.Runtime.execute_shell`,
  because a ``shell=True`` flag is too easy to set by accident.
* **Kryon is not a sandbox.** Its timeouts and output caps manage resources; they do not
  contain a hostile program. See ``docs/security/threat-model.md``.
"""

from kryon.errors import (
    CommandNotFound,
    InvalidArguments,
    KryonError,
    PermissionDenied,
    ProcessCancelled,
    ProcessFailed,
    ProcessStartFailed,
    ProcessTimeout,
    ResourceLimitExceeded,
    UnsupportedPlatform,
)
from kryon.model import ExecutionOptions, ExecutionResult, Stream, TerminationReason
from kryon.runtime import Process, Runtime

__version__ = "1.0.0"

# Grouped by concept rather than sorted: this list doubles as the map of the package.
__all__ = [  # noqa: RUF022
    "__version__",
    # runtime
    "Runtime",
    "Process",
    # model
    "ExecutionOptions",
    "ExecutionResult",
    "Stream",
    "TerminationReason",
    # errors
    "KryonError",
    "InvalidArguments",
    "CommandNotFound",
    "PermissionDenied",
    "ProcessStartFailed",
    "ProcessFailed",
    "ProcessTimeout",
    "ProcessCancelled",
    "ResourceLimitExceeded",
    "UnsupportedPlatform",
]


def __getattr__(name: str) -> object:
    """Point people at :mod:`kryon.aio` instead of failing with a bare AttributeError."""
    if name in ("AsyncRuntime", "AsyncProcess"):
        raise AttributeError(
            f"{name} lives in kryon.aio to keep asyncio out of the import path for "
            f"synchronous callers: from kryon.aio import {name}"
        )
    raise AttributeError(f"module {__name__!r} has no attribute {name!r}")
