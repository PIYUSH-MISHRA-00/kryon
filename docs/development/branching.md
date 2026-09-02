# Branching Model

```
main ─────────────────────────────────────────────▶  integration + release
  ├── python ─────────────────────────────────────▶  Python SDK development
  ├── javascript ─────────────────────────────────▶  TypeScript SDK development
  ├── dart ───────────────────────────────────────▶  Dart SDK development
  ├── java ───────────────────────────────────────▶  Java SDK development
  └── kotlin ─────────────────────────────────────▶  Kotlin SDK development
```

## `main`

The public face of the project and the branch releases are cut from. It holds the whole
repository: specification, conformance corpus, documentation, website, CI, community files,
and every SDK that has been integrated.

`main` must always be in a state you would be happy for a stranger to find. Its CI is green,
its documentation matches its code, and it claims nothing that is not implemented.

## Language branches

One long-lived branch per ecosystem: `python`, `javascript`, `dart`, `java`, `kotlin`.

Each is the development line for that SDK. They exist because the five ecosystems move at
different speeds and have different toolchains — a Dart contributor should not have their
work blocked by a Kotlin build, and a Java refactor in progress should not sit in `main` for
three weeks.

They are branched from `main`, kept current with it, and merged back when the work is ready.
They are **not** forks: the specification and the conformance corpus live on `main` and are
authoritative there. A language branch that changes an expectation is proposing a
specification change, and that change goes to `main` through its own pull request.

### One deliberate divergence: the root README

Each language branch replaces the repository's root `README.md` with that SDK's own README, so
that someone landing on `github.com/PIYUSH-MISHRA-00/kryon/tree/dart` sees Dart documentation
rather than a project overview that buries it.

That is the *only* file that differs. It does mean a merge from `main` conflicts on `README.md`,
and the resolution is always the same:

```bash
git checkout <language>
git merge main
git checkout --ours README.md      # keep the branch's SDK README
git add README.md && git commit
```

The same content lives at `<language>/README.md` on `main`, so nothing is lost and nothing
silently drifts — the branch root README is a copy of that file, updated with it.

## Feature branches

Short-lived, named by type and scope:

```
feat/python-pty-session
feat/js-execution-core
fix/python-timeout-orphan
docs/security-remote-execution
ci/python-matrix
build/dart-package-metadata
```

| Prefix | For |
|---|---|
| `feat/` | New capability |
| `fix/` | Bug fix |
| `docs/` | Documentation |
| `spec/` | Specification change |
| `test/` | Tests only |
| `ci/` | Workflows and automation |
| `build/` | Packaging and build configuration |
| `perf/` | Performance work |
| `security/` | Security fixes and hardening |

Branch from the branch you are targeting: SDK work from that SDK's branch, everything else
from `main`.

## Flow

```
feat/python-pty-session  ──▶  python  ──▶  main  ──▶  tag v0.2.0
docs/threat-model        ─────────────▶  main
spec/transport-messages  ─────────────▶  main  ──▶  merged down to language branches
```

A specification change lands on `main` first, then flows down. An implementation change lands
on its language branch first, then flows up. That direction matters: it is what keeps the
specification the source of truth rather than a summary of whatever Python happened to do.

## Commits

[Conventional Commits](https://www.conventionalcommits.org/), with a scope where one applies:

```
feat(python): add PTY session support
fix(python): terminate orphaned child after timeout
docs(security): document the remote execution architecture
spec: define transport message set
test(python): add conformance runner for spawn cases
ci: run the Python matrix on Windows and macOS
build(python): publish sdist alongside the wheel
```

Types: `feat`, `fix`, `docs`, `spec`, `test`, `ci`, `build`, `refactor`, `perf`, `security`,
`chore`.

Breaking changes get a `!` and a `BREAKING CHANGE:` footer explaining what to do instead.
Pre-`1.0` that is allowed; silently changing behaviour is not.

## Pull requests

- Target the right branch (SDK work → that SDK's branch; everything else → `main`).
- Keep it to one logical change. A refactor plus a fix is two pull requests.
- Fill in the template — especially the security and platform sections. They are there
  because this project executes commands.
- CI must be green. Do not disable a failing check; fix the cause.

## Releases

Tags are cut from `main` only, as `vMAJOR.MINOR.PATCH`. See [releases](releases.md).

## Rules

- Never force-push `main` or a language branch.
- Never rewrite published history.
- Delete feature branches after merging.
- Keep language branches current with `main`; a branch that has drifted for months is a merge
  conflict pretending to be progress.
