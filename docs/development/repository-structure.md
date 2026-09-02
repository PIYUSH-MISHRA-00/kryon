# Repository Structure

```
kryon/
├── spec/                 The normative, language-neutral contract
│   ├── execution.md          one-shot execution
│   ├── process.md            long-lived processes
│   ├── errors.md             the error taxonomy
│   ├── terminal.md           PTY + emulator model (design)
│   ├── transport.md          transport interface (design)
│   └── conformance.md        the helper contract and corpus format
│
├── tests/conformance/    The shared corpus every SDK runs
│   └── cases.json            one file, never forked per language
│
├── python/               Python SDK
│   └── src/kryon/  tests/  pyproject.toml
├── javascript/           TypeScript SDK
│   └── src/  test/  package.json  tsconfig.json
├── dart/                 Dart SDK
│   └── lib/  test/  example/  pubspec.yaml
├── java/                 Java SDK
│   └── src/  build.gradle.kts  gradlew
├── kotlin/               Kotlin SDK
│   └── src/  build.gradle.kts  gradlew
│
├── docs/                 Explanatory documentation
│   ├── getting-started/
│   ├── architecture/
│   ├── security/             read before deploying anything
│   ├── development/
│   └── guides/
│
├── website/              Static site. No build step.
├── examples/             Runnable examples, per language
├── benchmarks/           Methodology and harness
├── scripts/              Repository-wide checks
└── .github/              Workflows, issue templates, dependabot
```

## Why it is laid out this way

### `spec/` is at the top level, not inside a language directory

The specification is the product. Five SDKs are implementations of it. Putting it under
`python/` would make it look like Python's documentation, and the next SDK would quietly
diverge.

### `tests/conformance/` is at the root, not duplicated per SDK

One `cases.json`, read by every SDK's test suite. Copying it into each language directory is
how five SDKs end up agreeing only on paper: a change to an expectation must change every
SDK's tests simultaneously, and a single shared file is what forces that.

Each SDK owns the *runner* that maps cases onto its API, plus a *helper* program in its own
language. Those are per-SDK; the corpus is not. See
[`spec/conformance.md`](../../spec/conformance.md).

### Each SDK is a self-contained package directory

`python/` is a complete, publishable package with its own `pyproject.toml`, README, LICENSE
and tests. You can `pip install ./python` without the rest of the repository. Future SDK
directories work the same way.

The `LICENSE` file in each package directory is a copy, not a mistake: a published wheel or
jar has to carry the licence text, and it cannot reach outside its own directory to find it.

### `docs/` explains; `spec/` specifies

They are different jobs and mixing them is why so many projects have documentation that
contradicts itself. The specification says *what must happen* and is normative. The docs say
*why it happens that way* and are allowed to be discursive. Where they disagree, the
specification wins and the docs have a bug.

### `website/` has no build step

Plain HTML, CSS and JavaScript. No framework, no bundler, no `node_modules`, no lockfile to
keep current, no build that breaks two years from now when its toolchain has moved on. Edit a
file, reload the page, `git push`.

A static marketing-and-docs site is precisely the case that does not need a framework, and
choosing one anyway would be at odds with what this project says about dependencies.

### `examples/` is runnable, not illustrative

Every example runs. Snippets that only exist in prose rot silently; files that run get
noticed when they break.

## What is deliberately absent

**A `core/` directory.** There is no shared native core — [why](../architecture/sdk-design.md#why-no-shared-native-core).
An empty `core/` would advertise an architecture that does not exist.

**Empty SDK directories.** Every SDK directory here contains a working, tested implementation.
A directory containing a placeholder would be worse than no directory: it looks like an
implementation to anyone browsing the repository. The
[status table](../../spec/README.md#implementation-status) remains the honest signal, and a new
SDK directory does not appear until it passes the corpus.

**A monorepo tool.** Five packages across four toolchains, each building independently with its
own ecosystem's standard commands. A workspace tool would have to understand pip, npm, pub and
Gradle at once — a harder problem than the one it would solve. `scripts/check.sh` runs whatever
is installed and skips the rest.

## Where things go

| Adding | Put it in |
|---|---|
| A new cross-language behaviour | `spec/` first, then `tests/conformance/cases.json`, then the SDKs |
| A Python-only fix | `python/src/kryon/` + a test in `python/tests/` |
| A test for behaviour every SDK must share | `tests/conformance/cases.json`, with a `why` |
| A test for behaviour only Python has | `python/tests/test_api.py` |
| An explanation of why something works as it does | `docs/` |
| A rule an SDK must follow | `spec/` |
| A runnable example | `examples/<language>/` |
| A new SDK | A new top-level directory — see [SDK design](../architecture/sdk-design.md#adding-an-sdk) |
