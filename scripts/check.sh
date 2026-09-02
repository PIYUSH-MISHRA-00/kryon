#!/usr/bin/env bash
# Run everything CI runs, for whatever toolchains are installed.
#
# An ecosystem whose toolchain is missing is skipped, not failed -- a contributor fixing a
# Python bug should not need a JDK, a Dart SDK and Node just to check their work.
#
#   scripts/check.sh

set -uo pipefail

cd "$(dirname "$0")/.."

# Two conformance cases need this in the runner's own environment. Dart and the JVM cannot
# set it themselves and will skip those cases with a reason without it.
export KRYON_CONFORMANCE_INHERITED=from-parent

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

PY=""
if have python3; then PY=$(command -v python3); elif have python; then PY=$(command -v python); fi

# ---------------------------------------------------------------- repository

if [ -n "$PY" ]; then
  run "corpus"    "$PY" scripts/check_corpus.py
  run "versions"  "$PY" scripts/check_versions.py
  run "secrets"   "$PY" scripts/check_secrets.py
  run "links"     "$PY" scripts/check_links.py
  run "website"   "$PY" scripts/check_website.py
else
  skip_it "repository checks" "no python on PATH"
fi

# -------------------------------------------------------------------- python

if [ -n "$PY" ]; then
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

# ---------------------------------------------------------------- typescript

if have npm && have node; then
  if [ -d javascript/node_modules ]; then
    run "typescript: types"  sh -c "cd javascript && npm run --silent typecheck"
    run "typescript: build"  sh -c "cd javascript && npm run --silent build"
    run "typescript: tests"  sh -c "cd javascript && node --test test/api.test.mjs test/conformance.test.mjs"
  else
    skip_it "typescript" "dependencies not installed — run: cd javascript && npm install"
  fi
else
  skip_it "typescript" "no node/npm on PATH"
fi

# ---------------------------------------------------------------------- dart

if have dart; then
  if [ -d dart/.dart_tool ]; then
    run "dart: analyze"  sh -c "cd dart && dart analyze --fatal-infos"
    run "dart: format"   sh -c "cd dart && dart format --output=none --set-exit-if-changed ."
    run "dart: tests"    sh -c "cd dart && dart test --timeout 120s"
  else
    skip_it "dart" "dependencies not resolved — run: cd dart && dart pub get"
  fi
else
  skip_it "dart" "no dart on PATH"
fi

# ----------------------------------------------------------------------- jvm

if have java; then
  run "java: build"    sh -c "cd java && ./gradlew build --no-daemon -q"
  run "kotlin: build"  sh -c "cd kotlin && ./gradlew build --no-daemon -q"
else
  skip_it "java" "no java on PATH"
  skip_it "kotlin" "no java on PATH"
fi

# -------------------------------------------------------------------- summary

printf '\n\033[1m%s\033[0m\n' "────────────────────────────────"
printf '%d passed, %d failed, %d skipped\n' "$pass" "$fail" "$skip"

[ "$fail" -eq 0 ] || exit 1
