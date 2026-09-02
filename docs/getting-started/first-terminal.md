# Your first commands

A walkthrough from one command to a live streaming process. Every example runs today
against the Python SDK.

## 1. Run a command

```python
from kryon import Runtime

runtime = Runtime(encoding="utf-8")

result = runtime.execute("git", ["status", "--porcelain"])

print(result.stdout)
print(result.exit_code)   # 0
print(result.ok)          # True
print(result.duration)    # 0.0231...
```

`encoding="utf-8"` makes `stdout` and `stderr` text. Leave it out and you get `bytes` —
Kryon will not guess a codec for you, because guessing is how mojibake gets baked into logs.

## 2. Handle failure

A non-zero exit is a result, not an exception:

```python
result = runtime.execute("git", ["diff", "--quiet"])

if result.exit_code == 1:
    print("there are uncommitted changes")
```

That matters: `git diff --quiet` exits `1` to mean "there are changes", and `grep` exits `1`
to mean "no match". If those raised, ordinary code would be wrong by default.

When you *do* want strictness, ask for it:

```python
from kryon import ProcessFailed

try:
    runtime.execute("git", ["push"], check=True)
except ProcessFailed as exc:
    print(exc.result.stderr)   # the error carries the result it came from
```

A missing executable always raises, with or without `check` — nothing ran, so there is no
result to hand you:

```python
from kryon import CommandNotFound

try:
    runtime.execute("definitely-not-installed")
except CommandNotFound as exc:
    print(exc)
```

## 3. Pass arguments safely

```python
filename = input("file: ")            # "; rm -rf ~"
runtime.execute("wc", ["-l", filename])
```

That is safe. The argument reaches `wc` as one literal string; no shell is involved, so
nothing in it can expand, glob, chain or substitute.

The shell version has to be asked for by name, and is not safe with that input:

```python
runtime.execute_shell(f"wc -l {filename}")   # command injection
```

See [command execution](../security/command-execution.md).

## 4. Control the environment

```python
result = runtime.execute("printenv", ["DATABASE_URL"], env={"DATABASE_URL": "postgres://…"})
```

`env` merges over the inherited environment. To run a command that must *not* see your
process's secrets, clear it and list exactly what is allowed:

```python
result = runtime.execute(
    "./untrusted-build.sh",
    clear_env=True,
    env={"PATH": "/usr/bin:/bin", "HOME": "/tmp/build"},
)
```

That is the environment allowlist, and it is the main mechanism for keeping credentials out
of a child process.

## 5. Set limits

```python
result = runtime.execute(
    "./slow-thing",
    timeout=30,             # terminate, wait kill_grace, then kill
    max_output_bytes=1 << 20,   # per stream; stops the process when exceeded
)

if result.termination.value == "TIMEOUT":
    print("gave up; here is what it managed to print:", result.stdout)
```

Output collected before the kill is kept, because it is the only evidence of what the
process was doing.

**These are not security boundaries.** A process that ignores `SIGTERM` keeps running until
the kill lands, and whatever it did before that is done. See the
[threat model](../security/threat-model.md).

## 6. Stream a long-running process

```python
from kryon import Stream

with runtime.spawn("pip", ["install", "numpy"]) as proc:
    for stream, chunk in proc:
        target = "stderr" if stream is Stream.STDERR else "stdout"
        print(f"[{target}] {chunk.decode()}", end="")

    result = proc.wait()
    print("finished:", result.exit_code)
```

Output arrives as it is produced. The `with` block guarantees the process cannot outlive
it — leave the block for any reason, including an exception, and Kryon terminates the
process and closes every pipe.

## 7. Have a conversation

```python
with runtime.spawn("python", ["-u", "-i"]) as proc:
    proc.write("print(2 ** 10)\n")
    proc.write("print('done')\n")
    proc.close_stdin()

    for _, chunk in proc:
        print(chunk.decode(), end="")
```

`-u` matters: a child that buffers its output will not send you anything until it exits, and
that will look like Kryon is broken when it is not. This is a property of the program you
are running, not of Kryon — and it is the single most common surprise when streaming.

Kryon cannot fix it from the outside. A real pseudo-terminal *can*, because programs
line-buffer when they believe they are talking to a terminal. That is what
[PTY support](../../spec/terminal.md) is for, and it is not implemented yet.

## 8. Do it asynchronously

```python
import asyncio
from kryon.aio import AsyncRuntime

async def main():
    runtime = AsyncRuntime(encoding="utf-8")

    # Run three commands concurrently.
    results = await asyncio.gather(
        runtime.execute("git", ["rev-parse", "HEAD"]),
        runtime.execute("git", ["status", "--porcelain"]),
        runtime.execute("git", ["log", "-1", "--format=%s"]),
    )
    for result in results:
        print(result.stdout.strip())

asyncio.run(main())
```

Cancellation is native. Cancel the task and Kryon terminates the child before the
`CancelledError` reaches you:

```python
task = asyncio.create_task(runtime.execute("./forever"))
await asyncio.sleep(1)
task.cancel()          # the process is gone by the time this finishes unwinding
```

## Next

- [Platform differences](../guides/platform-support.md) — what changes on Windows
- [Threat model](../security/threat-model.md) — before this touches untrusted input
- [Examples](../../examples/python) — runnable versions of all of the above
