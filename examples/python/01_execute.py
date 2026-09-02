"""Running a command and understanding what came back.

    python examples/python/01_execute.py

Uses the Python interpreter itself rather than `echo` or `ls`, so it behaves identically on
Linux, macOS and Windows and depends on nothing being installed.
"""

import sys

from kryon import CommandNotFound, ProcessFailed, Runtime

runtime = Runtime(encoding="utf-8", timeout=30)

# ---------------------------------------------------------------- the basics

result = runtime.execute(sys.executable, ["-c", "print('Hello from Kryon')"])

print(f"stdout:      {result.stdout!r}")
print(f"exit code:   {result.exit_code}")
print(f"ok:          {result.ok}")
print(f"duration:    {result.duration:.3f}s")
print(f"termination: {result.termination.value}")
print(f"pid:         {result.pid}")

# ------------------------------------------------- arguments are never parsed

# No shell sees this. It is one argument containing spaces, semicolons and a dollar sign,
# and the program receives it exactly as written.
hostile = "$HOME && rm -rf / ; `whoami`"
result = runtime.execute(sys.executable, ["-c", "import sys; print(sys.argv[1])", hostile])

print(f"\npassed through literally: {result.stdout.strip() == hostile}")

# ------------------------------------------------ a non-zero exit is a result

result = runtime.execute(sys.executable, ["-c", "import sys; sys.exit(3)"])

print(f"\nexit code {result.exit_code}, ok={result.ok} -- returned, not raised")
print("That matters: grep exits 1 to mean 'no match', and git diff --quiet exits 1")
print("to mean 'there are changes'. Raising on those makes ordinary code wrong.")

# ------------------------------------------------------ stderr, and check=True

script = "import sys; print('out'); print('boom', file=sys.stderr); sys.exit(2)"

result = runtime.execute(sys.executable, ["-c", script])
print(f"\nstdout={result.stdout!r} stderr={result.stderr!r}")

try:
    runtime.execute(sys.executable, ["-c", script], check=True)
except ProcessFailed as exc:
    print(f"\ncheck=True raised: {type(exc).__name__}")
    # The error carries the result. An exception that discards the stderr explaining
    # what went wrong is a worse error than no error.
    print(f"and it still has the result: exit_code={exc.result.exit_code}")

# ------------------------------------------- failing to start is always an error

try:
    runtime.execute("kryon-definitely-not-installed")
except CommandNotFound as exc:
    print(f"\n{type(exc).__name__}: {exc}")
    print("Raised with or without check=True -- nothing ran, so there is no result")
    print("to hand back, and a synthetic one would be a flag every caller forgets.")

# ------------------------------------------------------------ shell execution

# Shell semantics have to be asked for by name. Never build this string from input you do
# not control -- see docs/security/command-execution.md.
result = runtime.execute_shell("exit 7")
print(f"\nexecute_shell('exit 7') -> exit code {result.exit_code}")
