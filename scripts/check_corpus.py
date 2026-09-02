#!/usr/bin/env python3
"""Validate the shared conformance corpus.

The corpus is read by every SDK's test suite. A malformed case, a duplicated id or a
missing `why` breaks five test suites at once, so it is checked here rather than
discovered by whoever adds the next SDK.

Dependency-free and standard-library only, so it runs anywhere the repository does.
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CORPUS = ROOT / "tests" / "conformance" / "cases.json"

VALID_APIS = {"execute", "execute_shell", "spawn"}
VALID_PLATFORMS = {"posix", "windows"}
ID_PATTERN = re.compile(r"^[a-z][a-z0-9_]*(\.[a-z0-9_]+)+$")

#: Verbs the helper contract defines. A case using anything else would silently pass in
#: whichever SDK happened to implement an extra verb, and fail everywhere else.
VALID_VERBS = {
    "echo", "raw", "err", "both", "exit", "env", "dumpenv", "cwd", "sleep",
    "spam", "cat", "lines", "unicode", "ansi", "ignoreterm",
}

VALID_EXPECTATIONS = {
    "exit_code", "signal_present", "termination", "ok", "stdout", "stdout_contains",
    "stdout_contains_last", "stdout_bytes_at_most", "stdout_is_bytes",
    "stdout_contains_bytes", "stdout_is_dir", "stderr", "stderr_contains",
    "stdout_truncated", "stderr_truncated", "duration_at_most", "duration_at_least",
    "raises", "streamed_chunks_at_least", "running_after_terminate",
    "running_after_scope",
}

VALID_TERMINATIONS = {"EXITED", "SIGNALED", "TIMEOUT", "CANCELLED", "OUTPUT_LIMIT"}


def main() -> int:
    problems: list[str] = []

    try:
        corpus = json.loads(CORPUS.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        print(f"FAIL  cannot read {CORPUS.relative_to(ROOT)}: {exc}")
        return 1

    cases = corpus.get("cases")
    if not isinstance(cases, list) or not cases:
        print("FAIL  corpus has no cases")
        return 1

    seen: set[str] = set()
    for index, case in enumerate(cases):
        cid = case.get("id", f"<case {index}>")

        if not case.get("id"):
            problems.append(f"case {index}: missing id")
        elif not ID_PATTERN.match(case["id"]):
            problems.append(f"{cid}: id must be lowercase dotted, e.g. execute.timeout.fires")
        elif case["id"] in seen:
            problems.append(f"{cid}: duplicate id -- ids identify regressions across five SDKs")
        seen.add(case.get("id", ""))

        if not case.get("why"):
            problems.append(
                f"{cid}: missing `why`. State what would break in the real world if this "
                "regressed, or nobody can safely delete this case in two years."
            )

        api = case.get("api")
        if api not in VALID_APIS:
            problems.append(f"{cid}: api must be one of {sorted(VALID_APIS)}, got {api!r}")

        if not isinstance(case.get("expect"), dict) or not case["expect"]:
            problems.append(f"{cid}: `expect` must be a non-empty object")
        else:
            unknown = set(case["expect"]) - VALID_EXPECTATIONS
            if unknown:
                problems.append(
                    f"{cid}: unknown expectation(s) {sorted(unknown)}. Add them to "
                    "spec/conformance.md and to every SDK runner before using them."
                )
            termination = case["expect"].get("termination")
            if termination is not None and termination not in VALID_TERMINATIONS:
                problems.append(f"{cid}: unknown termination {termination!r}")

        for platform in case.get("platforms", []):
            if platform not in VALID_PLATFORMS:
                problems.append(f"{cid}: unknown platform {platform!r}")

        # A case that names its own executable is opting out of the helper (missing
        # executable, empty executable). Everything else must use a helper verb, or it is
        # not portable and the corpus stops meaning the same thing everywhere.
        if "executable" not in case and "shell_command" not in case:
            args = case.get("args") or []
            if not args:
                problems.append(f"{cid}: no helper verb given")
            elif args[0] not in VALID_VERBS:
                problems.append(
                    f"{cid}: {args[0]!r} is not a helper verb. Cases must not reference "
                    "real commands -- they differ across platforms and half of them do "
                    "not exist on Windows. See spec/conformance.md."
                )

    if problems:
        print(f"FAIL  {len(problems)} problem(s) in the conformance corpus:\n")
        for problem in problems:
            print(f"  - {problem}")
        return 1

    apis = {case["api"] for case in cases}
    print(f"OK    {len(cases)} conformance cases, covering {', '.join(sorted(apis))}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
