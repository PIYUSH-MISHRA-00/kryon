# Conformance Specification

**Status:** Draft — `0.1`

Defines the shared test corpus that every Kryon SDK must pass, and the mechanism that makes
one corpus runnable from five languages.

An SDK that has not run this corpus **MUST NOT** be described as conformant, in its README,
its package metadata, or the [status table](README.md#implementation-status).

## 1. The problem this solves

Five SDKs written by different people at different times will drift. Each will have tests,
each will pass, and they will still disagree about what `timeout` does to output collected
before the kill, or whether an empty `env` clears the environment.

The corpus fixes the *expectations* in one language-neutral file. Each SDK supplies a thin
runner that maps a case onto its own API. Disagreements then show up as test failures rather
than as bug reports two years later.

## 2. The helper contract

Conformance cases cannot reference `echo`, `sleep` or `/bin/sh` — their behaviour differs
across platforms and some do not exist on Windows. Instead, each SDK provides a **helper
program** in its own language, and cases invoke that.

The helper is invoked as `<helper> <verb> [args...]` and **MUST** implement:

| Verb | Behaviour |
|---|---|
| `echo TEXT...` | Write the arguments to stdout, space-separated, followed by `\n`. |
| `raw TEXT` | Write `TEXT` to stdout with no trailing newline and no interpretation. |
| `err TEXT...` | Write the arguments to stderr, space-separated, followed by `\n`. |
| `both OUT ERR` | Write `OUT\n` to stdout and `ERR\n` to stderr. |
| `exit CODE` | Exit immediately with the given status. |
| `env NAME` | Write the value of `NAME` to stdout, or nothing if unset, followed by `\n`. |
| `dumpenv` | Write every environment variable as `KEY=VALUE\n`, sorted, to stdout. |
| `cwd` | Write the working directory to stdout, followed by `\n`. |
| `sleep SECONDS` | Sleep, then exit `0`. Fractional seconds are permitted. |
| `spam BYTES` | Write `BYTES` bytes of `x` to stdout as fast as possible, then exit `0`. |
| `cat` | Copy stdin to stdout until end-of-input, then exit `0`. |
| `lines COUNT DELAY` | Write `line N\n` for `N` in `0..COUNT`, flushing and sleeping `DELAY` between each. |
| `unicode` | Write `héllo · 世界 · 🚀\n` as UTF-8 to stdout. |
| `ansi` | Write `\x1b[31mred\x1b[0m\n` to stdout. |
| `ignoreterm SECONDS` | Ignore the polite termination signal where the platform allows, then exit `0` after `SECONDS`. |

The helper **MUST** write stdout unbuffered or flush after every write. A buffered helper
turns streaming tests into false failures.

Every verb is deliberately trivial to implement — the whole helper is under fifty lines in
any of the five target languages. This is the price of a corpus that means the same thing
everywhere, and it is cheap.

## 3. The corpus

[`tests/conformance/cases.json`](../tests/conformance/cases.json) holds the cases. Each is:

```json
{
  "id": "execute.exit_code.nonzero",
  "api": "execute",
  "args": ["exit", "3"],
  "options": { "timeout": 10 },
  "expect": { "exit_code": 3, "termination": "EXITED", "ok": false }
}
```

| Field | Meaning |
|---|---|
| `id` | Stable dotted identifier. Never reused for a different case. |
| `api` | `execute`, `execute_shell` or `spawn`. |
| `args` | Arguments passed to the helper. |
| `options` | Execution options, using the conceptual names from [`execution.md`](execution.md). |
| `stdin` | Text written to the child's stdin, where applicable. |
| `expect` | Assertions. Absent keys are not asserted. |
| `platforms` | Optional allowlist: `posix`, `windows`. Omitted means all. |
| `why` | Prose stating what would break in the real world if this case regressed. |

`expect` supports `exit_code`, `signal_present`, `termination`, `ok`, `stdout`,
`stdout_contains`, `stdout_bytes_at_most`, `stderr`, `stderr_contains`, `stdout_truncated`,
`duration_at_most`, `duration_at_least`, and `raises`.

### 3.1 Coverage requirements

The corpus **MUST** cover, at minimum:

- **Execution** — success, non-zero exit, stdout, stderr, both interleaved, argument vector
  passed literally, missing executable, unexecutable file.
- **Environment** — inherited, merged, removed by null, cleared, allowlisted.
- **Working directory** — honoured, non-existent directory rejected.
- **Input** — stdin delivered, stdin closed, empty stdin.
- **Timeouts** — fires, partial output preserved, no orphan left, survives a process that
  ignores the polite signal.
- **Limits** — output cap enforced, truncation flagged, process stopped.
- **Encoding** — UTF-8 round-trip, multi-byte characters split across chunk boundaries,
  bytes mode returns bytes.
- **Streaming** — chunks arrive before exit, backpressure honoured, both streams tagged.
- **Lifecycle** — terminate, kill, wait, scope exit terminates a running process.
- **Shell** — `execute_shell` interprets, `execute` does not.

### 3.2 Platform differences

Where behaviour legitimately differs, the case is restricted with `platforms` and a matching
case is added for the other platform. Differences **MUST NOT** be handled by loosening an
assertion until it passes everywhere — that hides the difference from exactly the people who
need to know about it. They are recorded in
[`docs/guides/platform-support.md`](../docs/guides/platform-support.md).

## 4. Running the corpus

Each SDK exposes a single command, documented in its README, that runs the corpus against
its own implementation:

| SDK | Command | Status |
|---|---|---|
| Python | `pytest tests/test_conformance.py` | Running |
| TypeScript | not yet | Not implemented |
| Dart | not yet | Not implemented |
| Java | not yet | Not implemented |
| Kotlin | not yet | Not implemented |

Every case **MUST** be attempted. An SDK that cannot yet satisfy a case **MUST** report it as
skipped with a reason, never silently drop it from the corpus. The count of skipped cases is
the honest measure of how far an SDK has to go.
