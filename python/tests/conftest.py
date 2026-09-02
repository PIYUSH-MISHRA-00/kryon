"""Shared fixtures.

Every test drives the conformance helper rather than real system commands, so the suite
behaves identically on Linux, macOS and Windows and never touches anything outside its
own temporary directory.
"""

from __future__ import annotations

import os
import sys
from pathlib import Path

import pytest

HELPER = str(Path(__file__).parent / "helper.py")

#: The repository-root corpus, shared with every other SDK.
CASES = Path(__file__).resolve().parents[2] / "tests" / "conformance" / "cases.json"


@pytest.fixture(scope="session")
def helper() -> list:
    """The argument vector prefix that runs the conformance helper.

    ``-u`` matters: a buffered helper turns streaming assertions into false failures.
    """
    return [sys.executable, "-u", HELPER]


@pytest.fixture
def runtime():
    from kryon import Runtime

    return Runtime()


@pytest.fixture
def async_runtime():
    from kryon.aio import AsyncRuntime

    return AsyncRuntime()


@pytest.fixture
def env_var(monkeypatch):
    """Set a variable in this process's environment for the duration of one test."""

    def _set(name: str, value: str) -> None:
        monkeypatch.setenv(name, value)

    return _set


@pytest.fixture(autouse=True)
def _no_inherited_test_vars(monkeypatch):
    """Start every test from a known state for the variables the corpus uses."""
    for name in ("KRYON_TEST_VAR", "KRYON_CONFORMANCE_INHERITED"):
        monkeypatch.delenv(name, raising=False)
    yield


def posix_only(reason: str = "POSIX-specific behaviour"):
    return pytest.mark.skipif(os.name == "nt", reason=reason)


def windows_only(reason: str = "Windows-specific behaviour"):
    return pytest.mark.skipif(os.name != "nt", reason=reason)
