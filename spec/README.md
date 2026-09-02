# Kryon Specification

**Status:** Draft — `0.1` · Normative for all Kryon SDKs

This directory defines Kryon as a *language-neutral behavioural contract*. It exists so
that the Python, JavaScript/TypeScript, Dart, Java and Kotlin SDKs are one product with
five idiomatic surfaces, rather than five unrelated libraries that happen to share a name.

The specification defines **semantics**, not syntax. Each SDK is expected to look and feel
native to its ecosystem — `snake_case` in Python, `camelCase` in TypeScript, suspending
functions in Kotlin, `Future`s in Dart — while producing the same observable behaviour for
the same inputs.

## Documents

| Document | Defines |
|---|---|
| [`execution.md`](execution.md) | One-shot command execution: options, results, termination |
| [`process.md`](process.md) | Long-lived processes: streaming, input, signals, lifecycle |
| [`errors.md`](errors.md) | The error taxonomy and which failures raise vs. report |
| [`terminal.md`](terminal.md) | PTY sessions and terminal emulation model (design, not yet implemented) |
| [`transport.md`](transport.md) | The pluggable transport interface (design, not yet implemented) |
| [`conformance.md`](conformance.md) | The shared test corpus every SDK must pass |

## Requirement levels

The key words **MUST**, **MUST NOT**, **SHOULD**, **SHOULD NOT** and **MAY** are used as
described in [RFC 2119](https://www.rfc-editor.org/rfc/rfc2119).

## Implementation status

| Area | Spec | Python | TypeScript | Dart | Java | Kotlin |
|---|---|---|---|---|---|---|
| Execution | Draft | Implemented | Not started | Not started | Not started | Not started |
| Process / streaming | Draft | Implemented | Not started | Not started | Not started | Not started |
| Errors | Draft | Implemented | Not started | Not started | Not started | Not started |
| PTY | Draft | Not started | Not started | Not started | Not started | Not started |
| Terminal emulation | Draft | Not started | Not started | Not started | Not started | Not started |
| Transports | Draft | Not started | Not started | Not started | Not started | Not started |

Nothing in this table is marked implemented until it exists and is covered by tests.

## Versioning

The specification is versioned with the project. Pre-`1.0`, the specification MAY change
in backwards-incompatible ways; such changes are recorded in [`CHANGELOG.md`](../CHANGELOG.md)
and MUST be reflected in every SDK before that SDK claims conformance to the new version.
