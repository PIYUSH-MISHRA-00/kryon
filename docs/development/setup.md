# Development Setup

## What you need

Only for the SDK you are working on. Contributing a Python fix does not require a JDK.

| Working on | Requires |
|---|---|
| Documentation, spec, website | Nothing but a text editor and a browser |
| Python SDK | Python 3.9+ |
| TypeScript SDK | Node 20+ |
| Dart SDK | Dart 3.0+ |
| Java SDK | JDK 17+ (the Gradle wrapper handles Gradle) |
| Kotlin SDK | JDK 17+ (the Gradle wrapper handles Gradle and Kotlin) |

## Python

```bash
git clone https://github.com/PIYUSH-MISHRA-00/kryon.git
cd kryon/python

python -m venv .venv
source .venv/bin/activate        # Windows: .venv\Scripts\activate

pip install -e ".[dev]"
```

The whole workflow:

```bash
pytest                    # unit tests + the shared conformance corpus, sync and async
pytest -k conformance     # just the corpus
ruff check .              # lint
ruff format .             # format
mypy                      # type check, strict
python -m build           # sdist + wheel
```

All four must pass before a pull request is ready. CI runs exactly these.

## TypeScript

```bash
cd javascript
npm install
npm run build
npm test           # unit tests + the shared conformance corpus
npm run typecheck
```

Tests use Node's built-in runner. There is no test framework dependency.

## Dart

```bash
cd dart
dart pub get
dart analyze       # must be clean
dart test
```

The conformance helper is compiled once to a native executable; `dart run` would recompile it on
every one of seventy invocations and turn a fast suite into a two-minute one.

## Java

```bash
cd java
./gradlew build    # compile, javadoc, sources jar, tests
./gradlew test
```

Compiled with `-Xlint:all -Werror`, so a warning fails the build. Targets Java 17 bytecode via
`--release` rather than a toolchain, so any modern JDK works without Gradle downloading a second
one.

## Kotlin

```bash
cd kotlin
./gradlew build
./gradlew test
```

`explicitApi()` and `allWarningsAsErrors` are both on.

## A note on `setup_env`

Two conformance cases need a variable set in the *test runner's own* environment. Python and
JavaScript set it themselves; Dart and the JVM cannot, so their runners skip those cases with a
reason unless it is already present:

```bash
KRYON_CONFORMANCE_INHERITED=from-parent dart test
KRYON_CONFORMANCE_INHERITED=from-parent ./gradlew test
```

CI sets it for every SDK. See [`spec/conformance.md`](../../spec/conformance.md) §3.2.

## The website

Plain HTML, CSS and JavaScript. No build step, no framework, no `node_modules`:

```bash
cd website
python -m http.server 8000
# http://localhost:8000
```

Edit a file, reload the page. That is the entire loop, and it is deliberate — see
[repository structure](repository-structure.md#website).

## Repository scripts

```bash
scripts/check.sh          # everything CI runs, for whatever is installed
```

The script skips ecosystems whose toolchain is absent rather than failing, so it is useful
on a machine that only has Python.

## Before you open a pull request

- [ ] The SDK you touched passes its tests, including the conformance corpus
- [ ] `ruff check .` and `ruff format --check .` are clean
- [ ] `mypy` is clean
- [ ] New behaviour has a test; a bug fix has a test that failed before the fix
- [ ] Cross-language behaviour has a [corpus case](testing.md#the-conformance-corpus) with a `why`
- [ ] Documentation is updated if behaviour changed
- [ ] No secrets, tokens, keys or `.env` files are staged

## Common problems

**`ModuleNotFoundError: No module named 'kryon'`** — install it: `pip install -e ".[dev]"`
from the `python/` directory.

**Conformance tests fail on Windows only** — check whether the case should carry a
`platforms` restriction. Do not loosen the assertion to make it pass everywhere; that hides
the difference from the people who need to know about it. See
[platform support](../guides/platform-support.md).

**Streaming tests hang** — the child is probably buffering. The helper is run with `-u` for
exactly this reason; a child that buffers sends nothing until it exits.

**A test leaves processes behind** — that is a real bug, not a test problem. Kryon must never
return control while leaving a process running.

## Getting help

- [Discussions](https://github.com/PIYUSH-MISHRA-00/kryon/discussions) for questions
- [Issues](https://github.com/PIYUSH-MISHRA-00/kryon/issues) for bugs and proposals
- [`SUPPORT.md`](../../SUPPORT.md) for which is which
- Security issues: [private advisory](https://github.com/PIYUSH-MISHRA-00/kryon/security/advisories/new), never a public issue
