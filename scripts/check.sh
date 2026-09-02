#!/usr/bin/env bash
# Run everything CI runs, for whatever toolchains are installed.
#
# An ecosystem whose toolchain is missing is skipped, not failed -- a contributor fixing a
# Python bug should not need a JDK. That is also why CI reports unimplemented SDKs as
# "not implemented" rather than passing vacuously.
#
#   scripts/check.sh

set -uo pipefail

cd "$(dirname "$0")/.."

pass=0
fail=0
skip=0

run() {
  local name="$1"
  shift
  printf '\n\033[1m── %s\033[0m\n' "$name"
  if "$@"; then
    pass=$((pass + 1))
  else
    printf '\033[31mFAILED: %s\033[0m\n' "$name"
    fail=$((fail + 1))
  fi
}

skip_it() {
  printf '\n\033[2m── %s — skipped (%s)\033[0m\n' "$1" "$2"
  skip=$((skip + 1))
}

have() { command -v "$1" >/dev/null 2>&1; }

# ---------------------------------------------------------------- repository

if have python3 || have python; then
  PY=$(command -v python3 || command -v python)
  run "corpus"   "$PY" scripts/check_corpus.py
  run "secrets"  "$PY" scripts/check_secrets.py
  run "links"    "$PY" scripts/check_links.py
  run "website"  "$PY" scripts/check_website.py
else
  skip_it "repository checks" "no python on PATH"
fi

# -------------------------------------------------------------------- python

if [ -d python ] && { have python3 || have python; }; then
  PY=$(command -v python3 || command -v python)
  if "$PY" -c "import kryon" >/dev/null 2>&1; then
    run "python: lint"    sh -c "cd python && '$PY' -m ruff check ."
    run "python: format"  sh -c "cd python && '$PY' -m ruff format --check ."
    run "python: types"   sh -c "cd python && '$PY' -m mypy"
    run "python: tests"   sh -c "cd python && '$PY' -m pytest -ra -q"
  else
    skip_it "python" "kryon is not installed — run: pip install -e 'python[dev]'"
  fi
else
  skip_it "python" "no python on PATH"
fi

# --------------------------------------------------- SDKs that do not exist yet

for sdk in javascript dart java kotlin; do
  [ -d "$sdk" ] || skip_it "$sdk" "SDK not implemented"
done

# -------------------------------------------------------------------- summary

printf '\n\033[1m%s\033[0m\n' "────────────────────────────────"
printf '%d passed, %d failed, %d skipped\n' "$pass" "$fail" "$skip"

[ "$fail" -eq 0 ] || exit 1
