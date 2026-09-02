# SDK Design

How five language SDKs stay one product instead of five libraries that share a logo.

## The failure mode being avoided

A project announces SDKs for five ecosystems. Each is written by whoever knew that language,
at a different time, against a README rather than a specification. Two years later:

- one treats a non-zero exit as an exception, three return it, one has a flag;
- three leave the process running after a timeout;
- `env={}` clears the environment in two of them and does nothing in three;
- output is decoded as UTF-8 in one, as the platform locale in another, and left as bytes in
  the rest;
- nobody can tell you which is which without reading the source.

They still all pass their own tests. The tests were written to match the behaviour.

## How Kryon prevents it

### 1. Semantics are specified, not implied

The [specification](../../spec/README.md) defines behaviour in language-neutral terms and is
normative. It is written *before* implementations, and changed deliberately when an
implementation proves it wrong.

### 2. The corpus is shared, not copied

[`tests/conformance/cases.json`](../../tests/conformance/cases.json) is one file, at the
repository root, read by every SDK's test suite. It is never forked into a language
directory. A change to an expectation changes every SDK's tests at once, which is exactly the
pressure needed.

Each case carries a `why`: what would break in the real world if this regressed. A case
without one is a case nobody can safely delete in two years, so the Python suite asserts that
every case has one.

### 3. Cases are portable by construction

The corpus cannot reference `echo`, `sleep` or `/bin/sh` — they differ across platforms and
half of them do not exist on Windows. Instead each SDK ships a **helper program** in its own
language implementing a fixed set of verbs (`echo`, `exit`, `env`, `cwd`, `sleep`, `spam`,
`cat`, `lines`, `unicode`, `ansi`, `ignoreterm`, …).

The helper is under fifty lines in any of the five languages. That is the price of a corpus
that means the same thing everywhere, and it is cheap.

### 4. Skips are visible

An SDK that cannot yet satisfy a case skips it *with a reason*. It never quietly drops it.
The number of skipped cases is the honest measure of how far an SDK has to go, and it is
visible in the test output.

## What is shared and what is not

| Shared | Per-SDK |
|---|---|
| Semantics (the specification) | Syntax and naming conventions |
| The conformance corpus | The helper implementation |
| Concept names (`ExecutionResult`, `TerminationReason`) | Idiomatic spelling of those names |
| The error taxonomy | Exception hierarchy and base classes |
| Option meanings and defaults | How options are passed |
| Platform behaviour documentation | Platform implementation |

Notably **not** shared: a native core. There is no C library that every SDK wraps.

## Why no shared native core

It is the obvious design, and it was rejected. A Rust or C core with five sets of bindings
would guarantee identical behaviour by construction — and would cost:

- FFI in five ecosystems, each with its own packaging pain (wheels per platform, prebuilt
  binaries for npm, JNI, `dart:ffi`);
- platform-specific wheels and binaries for every OS and architecture combination;
- the loss of native async integration — a core with its own event loop does not compose with
  `asyncio`, or Node's loop, or Kotlin coroutines, or Dart's isolates, and papering over that
  produces the worst API in each ecosystem;
- a build toolchain requirement for contributors who only wanted to fix a Python bug.

For layer 1, every language's standard library already provides the primitives. The value
Kryon adds is *correct orchestration of them* — the termination sequence, bounded buffers,
the environment rules, the error taxonomy — and that is specification-shaped, not
binary-shaped.

This calculus may change for the terminal emulator, where the logic is large, intricate, pure
and identical everywhere. An emulator is a plausible future shared core precisely because it
has no I/O to integrate with. That decision will be made when the emulator exists, on
evidence.

## Making each SDK feel native

The specification defines semantics; it explicitly does not define syntax. Each SDK is
expected to look like it belongs in its ecosystem:

| Concept | Python | TypeScript | Dart | Java | Kotlin |
|---|---|---|---|---|---|
| Naming | `snake_case` | `camelCase` | `camelCase` | `camelCase` | `camelCase` |
| Async | `kryon.aio` | promises | `Future` | `CompletableFuture` | `suspend` |
| Streaming | iterator | async iterable | `Stream` | `Flow`-like | `Flow` |
| Options | keyword arguments | options object | named parameters | builder | named args + defaults |
| Resource scope | `with` | `await using` | `try`/`finally` | try-with-resources | `use` |
| Errors | exceptions | `Error` subclasses | exceptions | checked/unchecked | exceptions |

An SDK that forces Python's `snake_case` into Kotlin, or Kotlin's builder pattern into
Python, has failed differently but just as badly.

## Adding an SDK

1. Read [`spec/`](../../spec/README.md) in full. All of it, including the parts about
   platform differences.
2. Implement the helper (§2 of [`spec/conformance.md`](../../spec/conformance.md)).
3. Write the corpus runner that maps each case onto your API. Skips are fine and expected;
   silent omissions are not.
4. Implement `execute`, then `spawn`, then the error taxonomy.
5. Get every applicable case passing or explicitly skipped.
6. Only then update the [status table](../../spec/README.md#implementation-status) — the
   corpus result is what entitles you to change that row.

Full process in [`CONTRIBUTING.md`](../../CONTRIBUTING.md).

## When the specification is wrong

It will be. An implementation will hit a platform reality the specification did not
anticipate, or a rule that is unimplementable in one ecosystem.

The answer is to change the specification, in a pull request, with the reason — not to
deviate quietly in one SDK. A deviation nobody wrote down is how the failure mode at the top
of this page begins.
