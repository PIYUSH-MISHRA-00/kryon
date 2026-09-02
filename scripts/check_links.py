#!/usr/bin/env python3
"""Fail if a relative link in the documentation points at nothing.

Documentation with dead links is documentation people stop trusting, and this project
cross-references heavily -- the security documents in particular are only useful if the
README actually reaches them.

External URLs are not fetched. A checker that makes network requests is a checker that
fails when someone else's site is down.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path
from urllib.parse import unquote

ROOT = Path(__file__).resolve().parent.parent

LINK = re.compile(r"\[[^\]]*\]\(([^)\s]+)(?:\s+\"[^\"]*\")?\)")
SKIP_PREFIXES = ("http://", "https://", "mailto:", "#", "tel:", "data:")
SKIP_DIRS = {".git", "node_modules", ".venv", "venv", "dist", "build", ".pytest_cache"}


def markdown_files() -> list[Path]:
    return sorted(
        path
        for path in ROOT.rglob("*.md")
        if not SKIP_DIRS.intersection(path.relative_to(ROOT).parts)
    )


def main() -> int:
    problems: list[str] = []
    checked = 0

    for path in markdown_files():
        try:
            text = path.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError):
            continue

        for match in LINK.finditer(text):
            target = match.group(1)
            if target.startswith(SKIP_PREFIXES):
                continue

            # Strip an anchor; anchors are not verified, only the file they live in.
            file_part = unquote(target.split("#", 1)[0])
            if not file_part:
                continue

            checked += 1
            resolved = (path.parent / file_part).resolve()
            if not resolved.exists():
                line = text[: match.start()].count("\n") + 1
                rel = path.relative_to(ROOT).as_posix()
                problems.append(f"{rel}:{line}: broken link -> {target}")

    if problems:
        print(f"FAIL  {len(problems)} broken link(s):\n")
        for problem in problems:
            print(f"  - {problem}")
        return 1

    print(f"OK    {checked} relative links resolve across {len(markdown_files())} documents")
    return 0


if __name__ == "__main__":
    sys.exit(main())
