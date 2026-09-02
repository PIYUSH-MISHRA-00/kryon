# Contributing to Kryon

Thanks for considering it. This document is the short version; the details live in
[`docs/development/`](docs/development/).

## The quickest useful contributions

**Try it and report what surprised you.** Kryon is `0.1.0`. An API that confused you is a
bug in the API, not in you.

**Fix documentation.** Anything wrong, unclear, or out of date. Especially anything that
implies a security property Kryon does not have — that is a genuine security bug, and it is
[in scope](SECURITY.md#scope).

**Add a conformance case.** If you found a behaviour that should be identical across
languages and is not tested, [add it](docs/development/testing.md#adding-a-case). This is the
highest-leverage contribution in the repository.

**Implement an SDK.** The largest and most valuable one. See
[adding an SDK](docs/architecture/sdk-design.md#adding-an-sdk).

## Setup

```bash
git clone https://github.com/PIYUSH-MISHRA-00/kryon.git
cd kryon/python
pip install -e ".[dev]"
pytest
```

You only need the toolchain for what you are working on. A Python fix does not require a JDK.
Full instructions: [development setup](docs/development/setup.md).

## Repository layout

| Directory | Contents |
|---|---|
| [`spec/`](spec/) | The normative, language-neutral contract |
| [`tests/conformance/`](tests/conformance/) | The shared corpus every SDK runs |
| [`python/`](python/) | Python SDK — implemented |
| [`docs/`](docs/) | Explanatory documentation |
| [`website/`](website/) | Static site, no build step |
| [`examples/`](examples/) | Runnable examples |

More detail and the reasoning: [repository structure](docs/development/repository-structure.md).

## Branches

```
main ──┬── python
       ├── javascript
       ├── dart
       ├── java
       └── kotlin
```

`main` is integration and release. Each language has a long-lived development branch. SDK
work targets that SDK's branch; documentation, specification, website and CI target `main`.

Feature branches: `feat/python-pty-session`, `fix/python-timeout-orphan`,
`docs/security-remote-execution`. Full model: [branching](docs/development/branching.md).

## Commits

[Conventional Commits](https://www.conventionalcommits.org/):

```
feat(python): add PTY session support
fix(python): terminate orphaned child after timeout
docs(security): document the remote execution architecture
spec: define transport message set
```

Types: `feat`, `fix`, `docs`, `spec`, `test`, `ci`, `build`, `refactor`, `perf`, `security`,
`chore`.

## Before opening a pull request

- [ ] `pytest` passes, conformance included
- [ ] `ruff check .` and `ruff format --check .` are clean
- [ ] `mypy` is clean
- [ ] New behaviour has a test; a bug fix has a test that failed before it
- [ ] Cross-language behaviour has a [corpus case](docs/development/testing.md#adding-a-case) with a `why`
- [ ] Documentation updated if behaviour changed
- [ ] No secrets, tokens, keys or `.env` files staged

Fill in the pull request template, especially the **security** and **platform** sections.
They exist because this project executes commands.

## What gets a pull request rejected

**A feature nobody asked for.** Open an issue first. An abstraction with one implementation
is a guess about the future written in code.

**A new dependency without justification.** Kryon's Python SDK has zero runtime dependencies
and intends to keep it that way. If a dependency is genuinely needed, the pull request should
argue for it: licence, maintenance, security history, platform support, transitive weight.

**Documentation that overstates.** No feature is described as working until it works and is
tested. "Coming soon", "planned" and "not implemented" are all fine. Claiming something
exists is not.

**A behaviour change without a specification change.** If it changes what an SDK does
observably, the [specification](spec/) changes first — otherwise the next SDK implements the
old rule and nobody notices for two years.

**Silently loosening a test.** A conformance case that fails on Windows gets a `platforms`
restriction and a documented difference, not a weaker assertion.

**Anything that makes the dangerous path easier.** A `shell=True` convenience flag will be
declined however well-argued. That is the whole point.

## Code style

Whatever the ecosystem's formatter says. Python: `ruff format`, 100 columns, `ruff check`
clean, `mypy --strict` clean.

Beyond formatting:

- **Small modules, clear interfaces.** No god classes, no thousand-line files.
- **No global mutable state.** Two libraries using Kryon in one application must not be able
  to interfere with each other.
- **Comments explain *why*.** The code already says what.
- **Errors name the thing that failed** and carry the evidence, without ever carrying the
  environment or stdin.
- **No placeholder implementations.** A function that returns a fake success is worse than a
  missing function.

## Adding an SDK

The process, in order:

1. Read [`spec/`](spec/) in full — including the platform-difference sections.
2. Implement the [helper](spec/conformance.md#2-the-helper-contract) in that language.
   Under fifty lines.
3. Write the corpus runner mapping each case onto your API. Skips are fine; silent omissions
   are not.
4. Implement `execute`, then `spawn`, then the error taxonomy.
5. Get every applicable case passing or explicitly skipped.
6. Then, and only then, update the [status table](spec/README.md#implementation-status).

Open an issue before starting so the work is not duplicated.

## Security

Never report a vulnerability in a public issue or pull request. Use
[private advisories](https://github.com/PIYUSH-MISHRA-00/kryon/security/advisories/new). See
[`SECURITY.md`](SECURITY.md).

When contributing code that touches execution, argument handling, environments or process
lifetime, say so in the pull request's security section. Those paths get reviewed harder, and
that is not a comment on you.

## Getting help

- [Discussions](https://github.com/PIYUSH-MISHRA-00/kryon/discussions) — questions, ideas,
  "is this a bug?"
- [Issues](https://github.com/PIYUSH-MISHRA-00/kryon/issues) — confirmed bugs, proposals
- [`SUPPORT.md`](SUPPORT.md) — which is which

## Code of Conduct

By participating you agree to the [Code of Conduct](CODE_OF_CONDUCT.md).

## Licence

Contributions are licensed under [Apache-2.0](LICENSE), the project's licence. There is no
CLA.
