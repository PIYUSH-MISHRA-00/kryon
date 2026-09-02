# Testing

Kryon executes commands, manages process lifetimes and races threads against operating-system
signals. Every one of those is a place where a bug is invisible until production. Testing is
not optional here.

## Three layers

| Layer | Lives in | Answers |
|---|---|---|
| **Unit** | `python/tests/test_api.py` | Does this SDK's own surface behave? |
| **Conformance** | `tests/conformance/cases.json` | Do all SDKs behave *the same*? |
| **Platform** | Marked cases and tests | What legitimately differs, and is it documented? |

## The conformance corpus

One JSON file at the repository root, read by every SDK's test suite, never forked into a
language directory. It is the mechanism that keeps five SDKs one product — see
[SDK design](../architecture/sdk-design.md).

### Adding a case

```json
{
  "id": "execute.timeout.keeps_partial_output",
  "api": "execute",
  "args": ["lines", "10000", "0.01"],
  "options": { "timeout": 1, "encoding": "utf-8" },
  "expect": { "termination": "TIMEOUT", "stdout_contains": "line 0" },
  "why": "Output collected before the kill is the only evidence of what the process was doing. Discarding it makes timeouts undebuggable."
}
```

Rules:

- **`id` is stable and never reused** for a different case. It is how a regression is
  identified across five languages and several years.
- **`why` is mandatory** and states what would break in the real world. A case without one is
  a case nobody can safely delete later, and the Python suite fails if any case lacks it.
- **Never reference real commands.** No `echo`, no `sleep`, no `/bin/sh` — they differ across
  platforms and half do not exist on Windows. Use the
  [helper verbs](../../spec/conformance.md#2-the-helper-contract).
- **Restrict platform-specific cases** with `platforms`, and write the counterpart case for
  the other platform where one exists.
- **Never loosen an assertion to make it pass everywhere.** That hides a real difference from
  the people who need to know about it.

### Running it

```bash
cd python && pytest -k conformance
```

Every case runs twice — once through `Runtime`, once through `AsyncRuntime` — because "the
async one has the same semantics" is a claim, and claims get tested.

## The helper

Cases invoke a small program each SDK provides in its own language, implementing a fixed set
of verbs. The Python one is [`python/tests/helper.py`](../../python/tests/helper.py); it is
about eighty lines and has no dependencies.

It is always run unbuffered. A helper that buffers turns every streaming assertion into a
false failure — which is also the single most common surprise when using Kryon against real
programs.

## Unit tests

For the parts of an SDK the corpus does not reach: option merging, error mapping, guards on
`Process`, the internal buffer and reason types.

What is worth pinning:

```python
def test_close_leaves_no_orphan(runtime, helper):
    proc = runtime.spawn(helper[0], [*helper[1:], "sleep", "30"])
    pid = proc.pid
    proc.close()
    with pytest.raises(ProcessLookupError):
        os.kill(pid, 0)
```

That test asserts a promise no type system can: nothing is left running.

## What must always be tested

Anything touching these has a test, no exceptions:

- **Termination.** Every path that stops a process — timeout, cancellation, output limit,
  scope exit, explicit `terminate()` — must be shown to actually stop it.
- **Orphans.** A test that proves the process is gone, not one that assumes it.
- **Both pipes at once.** An implementation that drains stdout to completion first deadlocks
  as soon as a process fills the stderr buffer. That deadlock does not show up until
  production, on the one command that logs a lot.
- **Argument literalness.** The security property. `execute.args.vector_is_literal` exists so
  that a future refactor cannot quietly introduce a shell.
- **Limits.** That the cap bounds memory during the flood, not after.
- **Encoding.** Multi-byte characters, including across chunk boundaries.

## Test hygiene

**No real system commands.** Tests drive the helper, so they behave identically everywhere
and depend on nothing being installed.

**Nothing outside a temporary directory.** No writes to the repository, the home directory or
anywhere else. Use pytest's `tmp_path`.

**No network.** Ever.

**Deterministic, not timing-dependent.** Where timing is unavoidable — a timeout must
actually elapse — assert generous bounds (`duration_at_most: 15` for a one-second timeout).
A test that fails on a loaded CI runner gets marked flaky and then gets ignored, and an
ignored test is worse than no test.

**No leaked processes between tests.** `filterwarnings = ["error::ResourceWarning"]` is set
so that an abandoned process fails the suite rather than slowly filling the runner.

## Running everything

```bash
cd python
pytest                      # everything
pytest -k conformance       # the shared corpus
pytest -k "not conformance" # unit tests only
pytest -x -vv               # stop at the first failure, verbose
pytest --timeout=30         # tighter per-test timeout
```

## CI

Python is tested on Linux, macOS and Windows across 3.9–3.14. The matrix is not decoration:
the Windows differences in [platform support](../guides/platform-support.md) were found by
running there, and would have been assumed away otherwise.

Workflows for the unimplemented SDKs report *not implemented* rather than passing vacuously.
A green check that means "we did not test anything" is worse than a missing check.
