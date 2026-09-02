# Development Setup

## What you need

Only for the SDK you are working on. Contributing a Python fix does not require a JDK.

| Working on | Requires |
|---|---|
| Python SDK | Python 3.9+ |
| Documentation, spec, website | Nothing but a text editor and a browser |
| JavaScript SDK | Node 20+ *(SDK not implemented yet)* |
| Dart SDK | Dart 3.0+ *(SDK not implemented yet)* |
| Java / Kotlin SDK | JDK 17+ *(SDKs not implemented yet)* |

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

- [ ] `pytest` passes, including the conformance corpus
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
