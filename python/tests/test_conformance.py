"""Runs the shared conformance corpus against the Python SDK.

The corpus lives at ``tests/conformance/cases.json`` in the repository root and is
language-neutral. This file is the Python *runner*: it maps each case onto the Python
API and asserts the expectations. Every SDK writes one of these; the corpus itself is
never forked.

Each case runs twice -- once through :class:`kryon.Runtime`, once through
:class:`kryon.aio.AsyncRuntime` -- because "the async one has the same semantics" is a
claim, and claims get tested.
"""

from __future__ import annotations

import asyncio
import json
import os
import time
from pathlib import Path

import pytest

import kryon
from kryon.aio import AsyncRuntime

#: The repository-root corpus, shared verbatim with every other SDK.
CASES = Path(__file__).resolve().parents[2] / "tests" / "conformance" / "cases.json"

CORPUS = json.loads(CASES.read_text(encoding="utf-8"))
ALL_CASES = CORPUS["cases"]

ERRORS = {
    "CommandNotFound": kryon.CommandNotFound,
    "PermissionDenied": kryon.PermissionDenied,
    "ProcessStartFailed": kryon.ProcessStartFailed,
    "InvalidArguments": kryon.InvalidArguments,
    "ProcessFailed": kryon.ProcessFailed,
    "ProcessTimeout": kryon.ProcessTimeout,
    "ProcessCancelled": kryon.ProcessCancelled,
    "ResourceLimitExceeded": kryon.ResourceLimitExceeded,
}


def _ids(cases):
    return [case["id"] for case in cases]


def _applicable(case) -> bool:
    platforms = case.get("platforms")
    if not platforms:
        return True
    return ("windows" if os.name == "nt" else "posix") in platforms


def _substitute(value, tmpdir: str):
    if isinstance(value, str):
        return value.replace("${TMPDIR}", tmpdir)
    if isinstance(value, dict):
        return {k: _substitute(v, tmpdir) for k, v in value.items()}
    return value


def _command(case, helper, tmpdir):
    """Resolve a case to ``(executable, arguments)`` for this SDK."""
    if "shell_command" in case:
        key = "windows" if os.name == "nt" else "posix"
        return case["shell_command"][key], None
    if "executable" in case:
        return case["executable"], [_substitute(a, tmpdir) for a in case.get("args", [])]
    return helper[0], [*helper[1:], *[_substitute(a, tmpdir) for a in case.get("args", [])]]


def _options(case, tmpdir):
    opts = dict(_substitute(case.get("options", {}), tmpdir))
    if "stdin" in case:
        opts["stdin"] = case["stdin"]
    return opts


def _decoded(value) -> str:
    return value.decode("utf-8", "replace") if isinstance(value, bytes) else value


def _assert_result(case, result, tmpdir: str) -> None:
    expect = case["expect"]
    cid = case["id"]

    if "exit_code" in expect:
        assert result.exit_code == expect["exit_code"], cid
    if "termination" in expect:
        assert result.termination.value == expect["termination"], cid
    if "ok" in expect:
        assert result.ok is expect["ok"], cid
    if "stdout" in expect:
        assert _decoded(result.stdout) == expect["stdout"], cid
    if "stderr" in expect:
        assert _decoded(result.stderr) == expect["stderr"], cid
    if "stdout_contains" in expect:
        assert expect["stdout_contains"] in _decoded(result.stdout), cid
    if "stderr_contains" in expect:
        assert expect["stderr_contains"] in _decoded(result.stderr), cid
    if "stdout_truncated" in expect:
        assert result.stdout_truncated is expect["stdout_truncated"], cid
    if "stdout_bytes_at_most" in expect:
        assert len(result.stdout) <= expect["stdout_bytes_at_most"], cid
    if "duration_at_most" in expect:
        assert result.duration <= expect["duration_at_most"], cid
    if "duration_at_least" in expect:
        assert result.duration >= expect["duration_at_least"], cid
    if "signal_present" in expect:
        assert (result.signal is not None) is expect["signal_present"], cid
    if expect.get("stdout_is_bytes"):
        assert isinstance(result.stdout, bytes), cid
    if "stdout_contains_bytes" in expect:
        needle = bytes.fromhex(expect["stdout_contains_bytes"])
        haystack = result.stdout if isinstance(result.stdout, bytes) else result.stdout.encode()
        assert needle in haystack, cid
    if "stdout_is_dir" in expect:
        expected = Path(_substitute(expect["stdout_is_dir"], tmpdir)).resolve()
        assert Path(_decoded(result.stdout).strip()).resolve() == expected, cid


EXECUTE_CASES = [c for c in ALL_CASES if c["api"] in ("execute", "execute_shell")]
SPAWN_CASES = [c for c in ALL_CASES if c["api"] == "spawn"]


@pytest.mark.parametrize("case", EXECUTE_CASES, ids=_ids(EXECUTE_CASES))
def test_execute_conformance(case, helper, monkeypatch, tmp_path):
    if not _applicable(case):
        pytest.skip(f"{case['id']} does not apply to this platform")
    for name, value in case.get("setup_env", {}).items():
        monkeypatch.setenv(name, value)

    tmpdir = str(tmp_path)
    executable, arguments = _command(case, helper, tmpdir)
    options = _options(case, tmpdir)
    runtime = kryon.Runtime()
    call = runtime.execute_shell if case["api"] == "execute_shell" else runtime.execute

    if "raises" in case["expect"]:
        with pytest.raises(ERRORS[case["expect"]["raises"]]):
            call(executable, **options) if arguments is None else call(
                executable, arguments, **options
            )
        return

    result = (
        call(executable, **options) if arguments is None else call(executable, arguments, **options)
    )
    _assert_result(case, result, tmpdir)


@pytest.mark.parametrize("case", EXECUTE_CASES, ids=_ids(EXECUTE_CASES))
async def test_execute_conformance_async(case, helper, monkeypatch, tmp_path):
    if not _applicable(case):
        pytest.skip(f"{case['id']} does not apply to this platform")
    for name, value in case.get("setup_env", {}).items():
        monkeypatch.setenv(name, value)

    tmpdir = str(tmp_path)
    executable, arguments = _command(case, helper, tmpdir)
    options = _options(case, tmpdir)
    runtime = AsyncRuntime()
    call = runtime.execute_shell if case["api"] == "execute_shell" else runtime.execute

    async def invoke():
        if arguments is None:
            return await call(executable, **options)
        return await call(executable, arguments, **options)

    if "raises" in case["expect"]:
        with pytest.raises(ERRORS[case["expect"]["raises"]]):
            await invoke()
        return

    _assert_result(case, await invoke(), tmpdir)


@pytest.mark.parametrize("case", SPAWN_CASES, ids=_ids(SPAWN_CASES))
def test_spawn_conformance(case, helper, tmp_path):
    if not _applicable(case):
        pytest.skip(f"{case['id']} does not apply to this platform")

    tmpdir = str(tmp_path)
    executable, arguments = _command(case, helper, tmpdir)
    options = _options(case, tmpdir)
    expect = case["expect"]
    runtime = kryon.Runtime()

    if case.get("scope_exit_only"):
        with runtime.spawn(executable, arguments, **options) as proc:
            assert proc.running
        assert proc.running is expect["running_after_scope"], case["id"]
        return

    with runtime.spawn(executable, arguments, **options) as proc:
        if case.get("terminate_after") is not None:
            time.sleep(case["terminate_after"])
            proc.terminate()
            result = proc.wait(timeout=20)
            assert proc.running is expect["running_after_terminate"], case["id"]
            if "duration_at_most" in expect:
                assert result.duration <= expect["duration_at_most"], case["id"]
            return

        for chunk in case.get("write", []):
            proc.write(chunk)
        if case.get("close_stdin"):
            proc.close_stdin()

        chunks = [data for _, data in proc.output]
        result = proc.wait(timeout=20)

    text = b"".join(chunks).decode("utf-8", "replace")
    if "streamed_chunks_at_least" in expect:
        assert len(chunks) >= expect["streamed_chunks_at_least"], case["id"]
    if "stdout_contains" in expect:
        assert expect["stdout_contains"] in text, case["id"]
    if "stdout_contains_last" in expect:
        assert expect["stdout_contains_last"] in text, case["id"]
    if "exit_code" in expect:
        assert result.exit_code == expect["exit_code"], case["id"]


@pytest.mark.parametrize("case", SPAWN_CASES, ids=_ids(SPAWN_CASES))
async def test_spawn_conformance_async(case, helper, tmp_path):
    if not _applicable(case):
        pytest.skip(f"{case['id']} does not apply to this platform")

    tmpdir = str(tmp_path)
    executable, arguments = _command(case, helper, tmpdir)
    options = _options(case, tmpdir)
    expect = case["expect"]
    runtime = AsyncRuntime()

    if case.get("scope_exit_only"):
        proc = await runtime.spawn(executable, arguments, **options)
        async with proc:
            assert proc.running
        assert proc.running is expect["running_after_scope"], case["id"]
        return

    proc = await runtime.spawn(executable, arguments, **options)
    async with proc:
        if case.get("terminate_after") is not None:
            await asyncio.sleep(case["terminate_after"])
            proc.terminate()
            result = await proc.wait(timeout=20)
            assert proc.running is expect["running_after_terminate"], case["id"]
            if "duration_at_most" in expect:
                assert result.duration <= expect["duration_at_most"], case["id"]
            return

        for chunk in case.get("write", []):
            await proc.write(chunk)
        if case.get("close_stdin"):
            proc.close_stdin()

        chunks = [data async for _, data in proc.output]
        result = await proc.wait(timeout=20)

    text = b"".join(chunks).decode("utf-8", "replace")
    if "streamed_chunks_at_least" in expect:
        assert len(chunks) >= expect["streamed_chunks_at_least"], case["id"]
    if "stdout_contains" in expect:
        assert expect["stdout_contains"] in text, case["id"]
    if "stdout_contains_last" in expect:
        assert expect["stdout_contains_last"] in text, case["id"]
    if "exit_code" in expect:
        assert result.exit_code == expect["exit_code"], case["id"]


def test_corpus_is_fully_attempted():
    """No case may be quietly dropped from the run.

    An SDK that cannot satisfy a case skips it with a reason. Silently narrowing the
    corpus is how five SDKs end up agreeing only on paper.
    """
    covered = {c["id"] for c in EXECUTE_CASES} | {c["id"] for c in SPAWN_CASES}
    assert covered == {c["id"] for c in ALL_CASES}, "some corpus cases have no runner"


def test_corpus_ids_are_unique():
    ids = [c["id"] for c in ALL_CASES]
    assert len(ids) == len(set(ids)), "duplicate case id in the corpus"


def test_every_case_explains_itself():
    """A case without a `why` is a case nobody can safely delete later."""
    missing = [c["id"] for c in ALL_CASES if not c.get("why")]
    assert not missing, f"cases missing a `why`: {missing}"
