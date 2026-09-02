# Error Specification

**Status:** `1.0` — implemented and verified by five SDKs

Defines Kryon's error taxonomy and, more importantly, the rule for *which* failures are
errors at all.

## 1. The rule

> **Failing to start is an error. Failing while running is a result.**

A command that could not be found never ran; there is no exit code, no output, no duration.
Returning a synthetic result for it would force every caller to check a flag they will
forget to check. So it raises.

A command that ran and exited `1` did run. That is information, not an exception — `grep`
exits `1` to mean "no match", and `git diff --quiet` exits `1` to mean "there are changes".
Treating those as exceptions makes ordinary code wrong by default. So they are reported in
`ExecutionResult`.

Callers who prefer the strict style opt in with `check`, which converts unsuccessful results
into raised errors. It never converts start failures into results.

## 2. Taxonomy

All Kryon errors descend from a single root — `KryonError` — so that callers can catch
everything Kryon raises without catching everything the runtime raises.

### 2.1 Start failures

| Error | Raised when |
|---|---|
| `CommandNotFound` | The executable could not be resolved on `PATH`, or the given path does not exist. |
| `PermissionDenied` | The executable exists but cannot be executed, or `cwd` cannot be entered. |
| `ProcessStartFailed` | The process could not be created for any other reason (bad `cwd`, resource exhaustion, platform refusal). Carries the underlying cause. |
| `InvalidArguments` | The request is malformed before any system call: empty executable, negative timeout, non-string argument. |

`InvalidArguments` **MUST** be raised before the process is created, not after. Validating a
request is cheaper than cleaning up a bad one.

### 2.2 Run failures (raised only with `check`, or from `Process` operations)

| Error | Meaning |
|---|---|
| `ProcessFailed` | The process exited with a non-zero status. Carries the `ExecutionResult`. |
| `ProcessTimeout` | `timeout` elapsed and Kryon terminated the process. Carries the partial `ExecutionResult`. |
| `ProcessCancelled` | The caller cancelled the operation and Kryon terminated the process. |
| `ResourceLimitExceeded` | An output or session limit was exceeded. Carries the partial result. |

Every run failure carries the result it came from. An error that discards the 4 KB of stderr
explaining what went wrong is a worse error than no error.

### 2.3 Capability failures

| Error | Meaning |
|---|---|
| `UnsupportedPlatform` | The operation cannot exist on this platform — `signal()` on Windows, process execution in a browser. |
| `PtyUnavailable` | PTY allocation failed on a platform that supports it. |
| `InvalidTerminalSize` | Terminal dimensions were zero, negative, or beyond the platform's limit. |

`UnsupportedPlatform` is deliberately distinct from `PtyUnavailable`: the first says *never*,
the second says *not right now*. Callers retry one and not the other.

### 2.4 Transport failures

| Error | Meaning |
|---|---|
| `TransportError` | The transport carrying the session failed — connection lost, protocol violation. |
| `AuthenticationError` | The transport rejected the credentials it was given. |

## 3. Error content

Every Kryon error **MUST** carry:

- a message naming the executable involved, where one exists;
- the underlying platform error, where one exists, without losing its type or code;
- the `ExecutionResult`, for run failures.

Every Kryon error **MUST NOT** carry:

- the contents of the environment;
- the contents of stdin;
- anything from an environment variable, unless the caller explicitly asked for it in the
  message.

Environments and stdin routinely hold credentials. An error message is the most-copied,
most-logged, most-pasted-into-issues string a library produces. See
[`docs/security/threat-model.md`](../docs/security/threat-model.md).

## 4. Naming across languages

Semantics are normative; names are idiomatic. An SDK **MUST** provide an error for each
concept above, **MUST** keep them distinguishable in a `catch`/`except`, and **MUST**
document its mapping. It **MUST NOT** collapse two concepts into one type because the
platform happens to report them the same way today.

Illustrative mapping:

| Concept | Python | TypeScript | Dart | Java / Kotlin |
|---|---|---|---|---|
| root | `KryonError` | `KryonError` | `KryonException` | `KryonException` |
| not found | `CommandNotFound` | `CommandNotFoundError` | `CommandNotFoundException` | `CommandNotFoundException` |
| timeout | `ProcessTimeout` | `ProcessTimeoutError` | `ProcessTimeoutException` | `ProcessTimeoutException` |

Where a language has an established base class for a concept — `TimeoutError` in Python,
`IOException` in Java — SDKs **SHOULD** inherit from it as well, so that Kryon errors are
catchable by code that predates Kryon.
