"""Controlling what a child process can see.

    python examples/python/02_environment_and_cwd.py

The environment is the most common way a secret escapes into a process you did not write.
This example shows the four things you can do about it.
"""

import os
import sys
import tempfile

from kryon import ProcessStartFailed, Runtime

runtime = Runtime(encoding="utf-8", timeout=30)

SHOW = "import os, sys; print(os.environ.get(sys.argv[1], '<unset>'))"
COUNT = "import os; print(len(os.environ))"

os.environ["KRYON_DEMO_SECRET"] = "hunter2"

# ------------------------------------------------------- 1. inherited by default

result = runtime.execute(sys.executable, ["-c", SHOW, "KRYON_DEMO_SECRET"])
print(f"inherited:            {result.stdout.strip()}")
print("  A child sees your whole environment unless you say otherwise. That is the")
print("  right default -- a process that cannot see PATH breaks confusingly -- and it")
print("  is also how credentials end up somewhere you did not intend.")

# ------------------------------------------------------------- 2. merged over

result = runtime.execute(
    sys.executable, ["-c", SHOW, "KRYON_DEMO_MODE"], env={"KRYON_DEMO_MODE": "debug"}
)
print(f"\nadded variable:       {result.stdout.strip()}")

# ----------------------------------------------------------- 3. removed by None

result = runtime.execute(
    sys.executable, ["-c", SHOW, "KRYON_DEMO_SECRET"], env={"KRYON_DEMO_SECRET": None}
)
print(f"removed with None:    {result.stdout.strip()}")

# ------------------------------------------------------- 4. cleared: an allowlist

# This is the important one. clear_env starts from nothing, and env lists exactly what is
# permitted through. It is the primary mechanism for keeping secrets out of a child.
result = runtime.execute(
    sys.executable,
    ["-c", SHOW, "KRYON_DEMO_SECRET"],
    clear_env=True,
    env={"KRYON_DEMO_ALLOWED": "yes"},
)
print(f"cleared environment:  {result.stdout.strip()}")

before = runtime.execute(sys.executable, ["-c", COUNT]).stdout.strip()
after = runtime.execute(
    sys.executable, ["-c", COUNT], clear_env=True, env={"KRYON_DEMO_ALLOWED": "yes"}
).stdout.strip()
print(f"variable count:       {before} inherited -> {after} with an allowlist")

if os.name == "nt":
    print("  On Windows, SystemRoot and SystemDrive survive clear_env: many binaries,")
    print("  including ones in System32, fail to start without them.")

# ------------------------------------------------------- working directories

with tempfile.TemporaryDirectory() as tmp:
    result = runtime.execute(sys.executable, ["-c", "import os; print(os.getcwd())"], cwd=tmp)
    print(f"\ncwd honoured:         {os.path.samefile(result.stdout.strip(), tmp)}")

try:
    runtime.execute(sys.executable, ["-c", "pass"], cwd="/kryon-no-such-directory")
except ProcessStartFailed as exc:
    print(f"\nbad cwd:              {type(exc).__name__}")
    print("  Never a silent fallback to the current directory. Running the right command")
    print("  in the wrong place is a data-corruption bug, not a crash, and those are the")
    print("  expensive ones.")
