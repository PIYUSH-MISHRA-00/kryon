# Installation

## Python

```bash
pip install kryon
```

Requires Python 3.9 or newer, on Linux, macOS or Windows. **Zero runtime dependencies** —
Kryon's job is to orchestrate the standard library's process facilities correctly, and
adding a dependency to do that would add supply-chain risk to a package that already runs
arbitrary programs.

> **Not yet published to PyPI.** The name is unclaimed as of the first release preparation,
> but nothing has been uploaded. Until it is, install from source:
>
> ```bash
> pip install "kryon @ git+https://github.com/PIYUSH-MISHRA-00/kryon.git#subdirectory=python"
> ```

Verify:

```python
import kryon
print(kryon.__version__)
print(kryon.Runtime().execute("git", ["--version"], encoding="utf-8").stdout)
```

## Other SDKs

| Ecosystem | Package | Registry | Status |
|---|---|---|---|
| Python | `kryon` | PyPI | Implemented, not yet published |
| JavaScript / TypeScript | `kryon` | npm | **Not implemented** |
| Dart | `kryon` | pub.dev | **Not implemented** |
| Java | `io.github.piyush-mishra-00:kryon` | Maven Central | **Not implemented** |
| Kotlin | `io.github.piyush-mishra-00:kryon-kotlin` | Maven Central | **Not implemented** |

Those four rows are the roadmap, not an install instruction. There is nothing to install
yet, and this table will not say otherwise until there is. Progress is tracked in
[`ROADMAP.md`](../../ROADMAP.md) and the
[implementation status table](../../spec/README.md#implementation-status).

The Maven coordinates use the `io.github.*` namespace because it is verifiable through
GitHub account ownership; Kryon does not own a domain and will not claim one it does not
have.

## From source

```bash
git clone https://github.com/PIYUSH-MISHRA-00/kryon.git
cd kryon/python
pip install -e ".[dev]"
pytest
```

See [development setup](../development/setup.md) for the full contributor workflow.

## Package name availability

`kryon` returned "not found" on PyPI, npm and pub.dev when this was written, which means it
was unclaimed at that moment — not that it is reserved. Names are claimed by publishing, and
Kryon has published nothing. If a name is taken by the time a release is ready, the conflict
will be documented and a coherent fallback chosen (a scope, not an unrelated word). See
[releases](../development/releases.md).
