# Command Execution: Arguments vs. the Shell

The single most important distinction in this project, and the one every command-execution
API gets wrong by making the dangerous option the convenient one.

## Two different things

### Direct execution — `execute()`

```python
runtime.execute("wc", ["-l", user_input])
```

The operating system receives an argument **vector**: `["wc", "-l", <whatever the user
typed>]`. No parsing happens. If the user typed `; rm -rf ~`, that is a filename — an odd
one, which `wc` will fail to open, and nothing else.

There is no escaping to get right, because there is no parser to escape for.

### Shell execution — `execute_shell()`

```python
runtime.execute_shell(f"wc -l {user_input}")
```

The operating system receives one string, hands it to `/bin/sh -c` (or `%COMSPEC% /c`), and
the shell parses it. With the same input the shell sees:

```sh
wc -l ; rm -rf ~
```

Two commands. The second one runs. This is a command-injection vulnerability, and it is the
most common serious bug in code that runs subprocesses.

## Why Kryon uses two method names instead of a flag

Nearly every ecosystem exposes this as a boolean:

```python
subprocess.run(cmd, shell=True)     # Python
child_process.exec(cmd)             # Node — shell is the *default* here
Runtime.getRuntime().exec(cmd)      # Java — string overload splits on whitespace
```

A boolean has three problems. It sits among a dozen other options, so it is easy to set
without thinking. It reads identically to every other option in a code review. And it is
frequently the *default*, which means the safe path is the one you have to know to ask for.

Kryon inverts that:

| | |
|---|---|
| `execute(exe, args)` | Safe. The only way to run a program with arguments. |
| `execute_shell(line)` | Dangerous. Different name, warning in the docstring, visible in every diff. |

There is deliberately **no** `shell=True` option, and the specification forbids SDKs from
adding one ([`spec/execution.md` §2](../../spec/execution.md#2-argument-vector-execution-is-the-default)).

## When shell execution is legitimate

It is not forbidden — sometimes you genuinely need what a shell does:

```python
runtime.execute_shell("git log --oneline | head -20")           # a pipeline
runtime.execute_shell("ls *.log")                               # globbing
runtime.execute_shell("echo $HOME")                             # expansion
```

The rule is simple and absolute: **the string must be a constant, or built only from values
you control.** The moment a variable in that f-string came from outside your program, you
have a vulnerability.

## Building pipelines without a shell

Usually you do not need one:

```python
# Instead of: execute_shell("git log --oneline | head -20")
result = runtime.execute("git", ["log", "--oneline", "-20"])

# Instead of: execute_shell(f"ls {directory}/*.log")
import glob
paths = glob.glob(os.path.join(directory, "*.log"))
result = runtime.execute("wc", ["-l", *paths])

# Instead of: execute_shell(f"cat {path} | grep {pattern}")
result = runtime.execute("grep", ["--", pattern, path])
```

That last one shows a second habit worth having: `--` ends option parsing, so a pattern
starting with `-` is treated as a pattern rather than a flag. Argument-vector execution stops
the *shell* interpreting your input; it does not stop the *program* interpreting it.

## What the argument vector still does not protect

Vector execution solves one problem completely. It leaves these:

**Option injection.** `execute("grep", [user_pattern, path])` with `user_pattern` set to
`--include=*` changes what `grep` does. Use `--` where the program supports it.

**Executable choice.** `execute(user_choice, args)` runs whatever the user named. The
argument vector is irrelevant if the attacker picks the program. Validate against an
allowlist.

**What the program itself does.** `execute("python", ["-c", user_code])` is arbitrary code
execution with a perfectly safe argument vector. Some programs are interpreters, and passing
them input is passing them code — `sh`, `python`, `node`, `awk`, `find -exec`, `git` with
`-c core.pager=…`, and many more.

**The environment.** A child inherits your environment unless you clear it. `LD_PRELOAD`,
`PYTHONPATH`, `PATH` and `GIT_*` all change what a program does before its first line runs.

## Windows

Windows has no `execve`. The kernel takes a single command-line **string**, and every process
parses it back into arguments using its own rules — usually, but not always, the Microsoft C
runtime's.

Kryon uses the standard library's quoting for this, because it is far better tested than
anything a library would write itself. The practical consequences:

- Argument-vector execution works, and is still the right default.
- A handful of programs (notably `cmd.exe` and some scripting hosts) parse their command
  lines differently, so an argument containing `&`, `|`, `^` or `%` can behave unexpectedly
  when the callee is one of them.
- `execute_shell` on Windows reaches `cmd.exe`, whose quoting rules are genuinely different
  from `sh`. A string that is safe on Linux is not necessarily safe there.

Treat Windows shell execution with more suspicion, not less.

## Checklist

- [ ] Every `execute_shell` call in the codebase has a constant string, or one built purely
      from values under your control.
- [ ] No user input ever chooses the executable, unless it is checked against an allowlist.
- [ ] `--` is used where the target program supports it.
- [ ] The environment is cleared or allowlisted when running anything you did not write.
- [ ] You know which of the programs you invoke are interpreters.
