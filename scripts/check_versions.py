#!/usr/bin/env python3
"""Fail if the five SDKs disagree about what version they are.

All SDKs share one version number, so that "Kryon 1.0" means the same set of capabilities in
every language. That promise is worth exactly as much as the check that enforces it: without
one, a release bumps four manifests and forgets the fifth, and the mismatch is discovered by
whoever installs the odd one out.

Also checks the version strings compiled *into* the code, which are what a caller actually
sees at runtime and which drift even more quietly than a manifest.

Standard library only.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

#: (label, file, pattern). The first capture group is the version.
SOURCES: list[tuple[str, str, str]] = [
    ("python package", "python/src/kryon/__init__.py", r'^__version__ = "([^"]+)"'),
    ("typescript package", "javascript/package.json", r'"version":\s*"([^"]+)"'),
    ("typescript VERSION", "javascript/src/index.ts", r'export const VERSION = "([^"]+)"'),
    ("typescript browser VERSION", "javascript/src/browser.ts", r'export const VERSION = "([^"]+)"'),
    ("dart package", "dart/pubspec.yaml", r"^version:\s*(\S+)"),
    ("dart kryonVersion", "dart/lib/kryon.dart", r"kryonVersion = '([^']+)'"),
    ("java package", "java/gradle.properties", r"^version=(\S+)"),
    ("kotlin package", "kotlin/gradle.properties", r"^version=(\S+)"),
]

#: Places the version is quoted in prose. Wrong here is a documentation bug, not a build
#: break, so these are reported separately.
DOCUMENTED = [
    ("java README (Gradle)", "java/README.md", r'io\.github\.piyush-mishra-00:kryon:([0-9][^"\)\s]*)'),
    ("kotlin README", "kotlin/README.md", r'io\.github\.piyush-mishra-00:kryon-kotlin:([0-9][^"\)\s]*)'),
    ("dart README", "dart/README.md", r"kryon:\s*\^([0-9][^\s]*)"),
]


def read(label: str, relative: str, pattern: str) -> tuple[str, str] | None:
    path = ROOT / relative
    try:
        text = path.read_text(encoding="utf-8")
    except OSError as exc:
        print(f"FAIL  cannot read {relative}: {exc}")
        return None
    match = re.search(pattern, text, re.MULTILINE)
    if not match:
        print(f"FAIL  no version found in {relative} (pattern {pattern!r})")
        return None
    return label, match.group(1)


def main() -> int:
    found: dict[str, str] = {}
    problems: list[str] = []

    for label, relative, pattern in SOURCES:
        result = read(label, relative, pattern)
        if result is None:
            return 1
        found[result[0]] = result[1]

    versions = set(found.values())
    if len(versions) != 1:
        problems.append("the SDKs disagree about the version:")
        for label, version in sorted(found.items()):
            problems.append(f"    {version:<12} {label}")

    canonical = next(iter(found.values()))

    for label, relative, pattern in DOCUMENTED:
        path = ROOT / relative
        if not path.exists():
            continue
        for quoted in set(re.findall(pattern, path.read_text(encoding="utf-8"))):
            if quoted != canonical:
                problems.append(
                    f"{relative} documents version {quoted}, but the package is {canonical}"
                )

    if problems:
        print("FAIL  version mismatch:\n")
        for problem in problems:
            print(f"  {problem}" if not problem.startswith("    ") else problem)
        print("\nAll SDKs share one version number. Bump them together.")
        return 1

    print(f"OK    all {len(found)} version declarations agree: {canonical}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
