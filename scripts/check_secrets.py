#!/usr/bin/env python3
"""Fail if anything that looks like a credential is committed.

Not a substitute for GitHub's own secret scanning -- it is the check that runs before a
push, on a project whose whole subject matter is executing commands with a process's
credentials in its environment.

Patterns are assembled from fragments so that this file does not match itself.
"""

from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# Assembled at runtime so the literal token prefixes never appear in this source.
PATTERNS: list[tuple[str, re.Pattern]] = [
    ("GitHub token", re.compile(r"\bgh[pousr]_[A-Za-z0-9]{36,}")),
    ("GitHub fine-grained token", re.compile(r"\bgithub" + r"_pat_[A-Za-z0-9_]{40,}")),
    ("PyPI token", re.compile(r"\bpypi-" + r"AgEIcHlwaS5vcmc[A-Za-z0-9_\-]{20,}")),
    ("npm token", re.compile(r"\bnpm_[A-Za-z0-9]{36}")),
    ("AWS access key", re.compile(r"\b(?:AKIA|ASIA)[0-9A-Z]{16}\b")),
    ("Slack token", re.compile(r"\bxox[abprs]-[0-9A-Za-z-]{10,}")),
    ("Google API key", re.compile(r"\bAIza[0-9A-Za-z_\-]{35}\b")),
    ("private key block", re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----")),
    ("SSH private key", re.compile(r"-----BEGIN OPENSSH PRIVATE KEY-----")),
    (
        "hardcoded credential",
        re.compile(
            r"""(?ix)
            \b(?:password|passwd|secret|api[_-]?key|access[_-]?token|auth[_-]?token)
            \s*[:=]\s*
            ['"][^'"\s${}<>]{12,}['"]
            """
        ),
    ),
]

#: Files that legitimately contain credential-shaped text: this checker, and the
#: documentation that explains what not to commit.
ALLOWLIST = {
    "scripts/check_secrets.py",
}

FORBIDDEN_NAMES = {
    ".env",
    ".npmrc",
    "credentials.json",
    "id_rsa",
    "id_ed25519",
    "secring.gpg",
}

FORBIDDEN_SUFFIXES = {".pem", ".key", ".p12", ".pfx", ".keystore", ".jks"}

BINARY_SUFFIXES = {".png", ".jpg", ".jpeg", ".gif", ".ico", ".woff", ".woff2", ".pdf", ".zip"}


def tracked_files() -> list[Path]:
    try:
        out = subprocess.run(  # noqa: S603
            ["git", "ls-files", "-z"],
            cwd=ROOT,
            capture_output=True,
            text=True,
            check=True,
        )
    except (OSError, subprocess.CalledProcessError):
        return [p for p in ROOT.rglob("*") if p.is_file() and ".git" not in p.parts]
    return [ROOT / name for name in out.stdout.split("\0") if name]


def main() -> int:
    problems: list[str] = []
    scanned = 0

    for path in tracked_files():
        rel = path.relative_to(ROOT).as_posix()

        if path.name in FORBIDDEN_NAMES or path.name.startswith("id_rsa"):
            problems.append(f"{rel}: this file must never be committed")
            continue
        if path.suffix.lower() in FORBIDDEN_SUFFIXES:
            problems.append(f"{rel}: key or certificate files must never be committed")
            continue
        if rel in ALLOWLIST or path.suffix.lower() in BINARY_SUFFIXES:
            continue
        if not path.is_file():
            continue

        try:
            text = path.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError):
            continue

        scanned += 1
        for label, pattern in PATTERNS:
            match = pattern.search(text)
            if match:
                line = text[: match.start()].count("\n") + 1
                problems.append(f"{rel}:{line}: possible {label}")

    if problems:
        print(f"FAIL  {len(problems)} possible secret(s):\n")
        for problem in problems:
            print(f"  - {problem}")
        print("\nRemove them, rotate anything real, and do not commit until this is clean.")
        return 1

    print(f"OK    no secrets found in {scanned} tracked text files")
    return 0


if __name__ == "__main__":
    sys.exit(main())
