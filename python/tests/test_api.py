"""Unit tests for behaviour the shared conformance corpus does not reach.

The corpus covers cross-language semantics. This file covers the Python surface itself:
option merging, error mapping, the guards on ``Process``, and the promise that nothing is
left running when a caller walks away.
"""

from __future__ import annotations

import asyncio
import os
import signal
import time

import pytest

import kryon
from kryon._core import Buffer, Reason
from kryon.model import ExecutionOptions, ExecutionResult, TerminationReason

posix_only = pytest.mark.skipif(os.name == "nt", reason="POSIX-specific behaviour")
windows_only = pytest.mark.skipif(os.name != "nt", reason="Windows-specific behaviour")


# -- options ---------------------------------------------------------------


def test_runtime_defaults_apply_to_calls(runtime, helper):
    runtime = kryon.Runtime(encoding="utf-8")
    result = runtime.execute(helper[0], [*helper[1:], "echo", "hi"])
    assert result.stdout == "hi\n"


def test_call_overrides_runtime_default(helper):
    runtime = kryon.Runtime(encoding="utf-8")
    result = runtime.execute(helper[0], [*helper[1:], "unicode"], encoding=None)
    assert isinstance(result.stdout, bytes)


def test_env_merges_rather_than_replaces(helper):
    runtime = kryon.Runtime(encoding="utf-8", env={"KRYON_A": "1"})
    result = runtime.execute(helper[0], [*helper[1:], "env", "KRYON_A"], env={"KRYON_B": "2"})
    assert result.stdout == "1\n", "a per-call env must not drop the runtime's env"


def test_unknown_option_names_the_valid_ones():
    with pytest.raises(kryon.InvalidArguments) as exc:
        kryon.Runtime(timeuot=5)
    assert "timeuot" in str(exc.value)
    assert "timeout" in str(exc.value), "the error should show what was meant"


def test_options_validate_rejects_zero_timeout():
    with pytest.raises(kryon.InvalidArguments):
        ExecutionOptions(timeout=0).validate()


def test_options_validate_rejects_zero_output_limit():
    with pytest.raises(kryon.InvalidArguments):
        ExecutionOptions(max_output_bytes=0).validate()


def test_options_validate_rejects_negative_grace():
    with pytest.raises(kryon.InvalidArguments):
        ExecutionOptions(kill_grace=-1).validate()


# -- argument validation ---------------------------------------------------


def test_passing_a_list_as_the_executable_says_what_to_do_instead(runtime):
    with pytest.raises(kryon.InvalidArguments) as exc:
        runtime.execute(["git", "status"])
    assert "execute('git', ['status'])" in str(exc.value).replace('"', "'")


def test_non_string_argument_is_rejected_with_its_index(runtime, helper):
    with pytest.raises(kryon.InvalidArguments) as exc:
        runtime.execute(helper[0], [*helper[1:], "echo", 42])
    assert "argument 3" in str(exc.value)


def test_none_executable_is_rejected(runtime):
    with pytest.raises(kryon.InvalidArguments):
        runtime.execute(None)


def test_shell_requires_a_string(runtime):
    with pytest.raises(kryon.InvalidArguments):
        runtime.execute_shell(["ls", "-l"])


def test_path_objects_are_accepted(runtime, tmp_path, helper):
    result = runtime.execute(helper[0], [*helper[1:], "cwd"], cwd=tmp_path, encoding="utf-8")
    assert result.ok


# -- results and errors ----------------------------------------------------


def _result(**kw) -> ExecutionResult:
    base = {
        "executable": "prog",
        "arguments": (),
        "exit_code": 0,
        "signal": None,
        "stdout": b"",
        "stderr": b"",
        "duration": 0.1,
        "termination": TerminationReason.EXITED,
    }
    base.update(kw)
    return ExecutionResult(**base)


def test_ok_requires_both_exited_and_zero():
    assert _result().ok
    assert not _result(exit_code=1).ok
    assert not _result(termination=TerminationReason.TIMEOUT, exit_code=0).ok


@pytest.mark.parametrize(
    ("termination", "error"),
    [
        (TerminationReason.TIMEOUT, kryon.ProcessTimeout),
        (TerminationReason.CANCELLED, kryon.ProcessCancelled),
        (TerminationReason.OUTPUT_LIMIT, kryon.ResourceLimitExceeded),
        (TerminationReason.SIGNALED, kryon.ProcessFailed),
    ],
)
def test_check_maps_each_termination_to_its_own_error(termination, error):
    with pytest.raises(error):
        _result(termination=termination, exit_code=None).check()


def test_check_returns_self_on_success():
    result = _result()
    assert result.check() is result


def test_errors_carry_the_result_they_came_from():
    with pytest.raises(kryon.ProcessFailed) as exc:
        _result(exit_code=2, stderr=b"the real reason\n").check()
    assert exc.value.result.exit_code == 2
    assert "the real reason" in str(exc.value), "stderr belongs in the message"


def test_error_message_excerpt_is_capped():
    with pytest.raises(kryon.ProcessFailed) as exc:
        _result(exit_code=1, stderr=b"x" * 5000).check()
    assert len(str(exc.value)) < 1000, "an error message is not a log file"


def test_kryon_errors_are_catchable_as_builtins(runtime):
    """Code written before Kryon should still catch what Kryon raises."""
    with pytest.raises(FileNotFoundError):
        runtime.execute("kryon-no-such-executable-xyzzy")
    assert issubclass(kryon.ProcessTimeout, TimeoutError)
    assert issubclass(kryon.PermissionDenied, PermissionError)
    assert issubclass(kryon.InvalidArguments, ValueError)


def test_every_kryon_error_descends_from_kryon_error():
    for name in kryon.__all__:
        attr = getattr(kryon, name)
        if isinstance(attr, type) and issubclass(attr, Exception):
            assert issubclass(attr, kryon.KryonError), name


def test_async_classes_are_not_silently_missing():
    with pytest.raises(AttributeError) as exc:
        kryon.AsyncRuntime  # noqa: B018
    assert "kryon.aio" in str(exc.value)


# -- process ---------------------------------------------------------------


def test_output_can_only_be_consumed_once(runtime, helper):
    with runtime.spawn(helper[0], [*helper[1:], "echo", "x"]) as proc:
        list(proc.output)
        with pytest.raises(ValueError, match="already being consumed"):
            proc.output  # noqa: B018


def test_write_after_close_stdin_raises(runtime, helper):
    with runtime.spawn(helper[0], [*helper[1:], "cat"]) as proc:
        proc.close_stdin()
        with pytest.raises(ValueError, match="closed"):
            proc.write("too late\n")


def test_close_is_idempotent(runtime, helper):
    proc = runtime.spawn(helper[0], [*helper[1:], "sleep", "30"])
    proc.close()
    proc.close()
    assert not proc.running


def test_wait_timeout_leaves_the_process_running(runtime, helper):
    with runtime.spawn(helper[0], [*helper[1:], "sleep", "30"]) as proc:
        with pytest.raises(kryon.ProcessTimeout):
            proc.wait(timeout=0.3)
        assert proc.running, "wait() is a wait, not a stop"


@windows_only()
def test_signal_is_unsupported_on_windows(runtime, helper):
    with (
        runtime.spawn(helper[0], [*helper[1:], "sleep", "30"]) as proc,
        pytest.raises(kryon.UnsupportedPlatform, match="terminate"),
    ):
        proc.signal(15)


@posix_only()
def test_signal_delivers(runtime, helper):
    with runtime.spawn(helper[0], [*helper[1:], "sleep", "30"]) as proc:
        proc.signal(signal.SIGTERM)
        result = proc.wait(timeout=10)
        assert not proc.running
        assert result.signal == signal.SIGTERM


@posix_only("needs os.kill(pid, 0) to probe for an orphan")
def test_close_leaves_no_orphan(runtime, helper):
    proc = runtime.spawn(helper[0], [*helper[1:], "sleep", "30"])
    pid = proc.pid
    proc.close()
    with pytest.raises(ProcessLookupError):
        os.kill(pid, 0)


@posix_only("needs os.kill(pid, 0) to probe for an orphan")
def test_unconsumed_output_does_not_block_close(runtime, helper):
    """A process nobody read from must still close promptly.

    The reader threads block on a full queue; without draining it on close, they and
    their file descriptors leak.
    """
    proc = runtime.spawn(helper[0], [*helper[1:], "spam", "50000000"])
    pid = proc.pid
    started = time.monotonic()
    proc.close()
    assert time.monotonic() - started < 15
    with pytest.raises(ProcessLookupError):
        os.kill(pid, 0)


# -- async -----------------------------------------------------------------


async def test_async_cancellation_terminates_the_child(async_runtime, helper):
    task = asyncio.ensure_future(async_runtime.execute(helper[0], [*helper[1:], "sleep", "30"]))
    await asyncio.sleep(0.4)
    task.cancel()
    started = time.monotonic()
    with pytest.raises(asyncio.CancelledError):
        await task
    assert time.monotonic() - started < 15, "cancellation must not wait out the process"


@posix_only("needs os.kill(pid, 0) to probe for an orphan")
async def test_async_close_leaves_no_orphan(async_runtime, helper):
    proc = await async_runtime.spawn(helper[0], [*helper[1:], "sleep", "30"])
    pid = proc.pid
    await proc.close()
    with pytest.raises(ProcessLookupError):
        os.kill(pid, 0)


async def test_async_output_can_only_be_consumed_once(async_runtime, helper):
    proc = await async_runtime.spawn(helper[0], [*helper[1:], "echo", "x"])
    async with proc:
        _ = [c async for c in proc.output]
        with pytest.raises(ValueError, match="already being consumed"):
            proc.output  # noqa: B018


# -- internals worth pinning ----------------------------------------------


def test_buffer_stops_at_its_limit():
    buf = Buffer(10)
    assert buf.add(b"12345") is False
    assert buf.add(b"67890") is False, "landing exactly on the limit is not truncation"
    assert buf.add(b"overflow") is True
    assert buf.value() == b"1234567890"
    assert buf.truncated


def test_buffer_without_a_limit_keeps_everything():
    buf = Buffer(None)
    buf.add(b"a" * 1000)
    assert len(buf.value()) == 1000
    assert not buf.truncated


def test_reason_is_first_writer_wins():
    reason = Reason()
    assert reason.set(TerminationReason.TIMEOUT) is True
    assert reason.set(TerminationReason.OUTPUT_LIMIT) is False
    assert reason.value is TerminationReason.TIMEOUT


def test_repr_is_useful_and_short(runtime, helper):
    result = runtime.execute(helper[0], [*helper[1:], "echo", "hi"])
    text = repr(result)
    assert "exit_code=0" in text and "EXITED" in text
    assert len(text) < 300, "a repr that dumps output is unusable in a debugger"
