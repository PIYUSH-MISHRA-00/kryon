# Summary

<!-- What does this change, in one or two sentences? -->

## Motivation

<!-- What problem does it solve? Link the issue or discussion if there is one. -->

Closes #

## Implementation

<!-- How does it work? Anything a reviewer would otherwise have to reverse-engineer:
     a non-obvious approach, an alternative you rejected, a trade-off you made. -->

## Tests

<!-- What did you add, and what would fail without this change? -->

- [ ] Unit tests added or updated
- [ ] A conformance case added, with a `why`, for behaviour every SDK must share
- [ ] A bug fix has a test that failed before the fix
- [ ] `pytest` passes locally
- [ ] Not applicable, because:

## Platform impact

<!-- Does this behave differently on Linux, macOS or Windows? Tested where? -->

- [ ] Behaves identically on all platforms
- [ ] Differs by platform, and the difference is documented in `docs/guides/platform-support.md`
- [ ] Windows-only or POSIX-only, and guarded accordingly

## Security impact

<!-- This project executes arbitrary commands. Please answer honestly; "I think it might"
     is far more useful than an unconsidered "none". -->

- [ ] No effect on command execution, argument handling, environments or process lifetime
- [ ] Touches one of those paths — described below

<!-- If it does, address these:
     - Can any input reach a shell that could not before?
     - Can anything now appear in an error, log or message that could not before?
     - Does any limit (timeout, output cap) become weaker or easier to bypass?
     - Can a process now outlive the call that started it? -->

## Breaking changes

- [ ] None
- [ ] Breaking, and described here with what callers should do instead:

<!-- Pre-1.0 breaking changes are allowed. Silent ones are not. -->

## Documentation

- [ ] Documentation updated
- [ ] Specification updated (required for any change to cross-language behaviour)
- [ ] `CHANGELOG.md` updated
- [ ] No documentation change needed, because:

## Checklist

- [ ] Targets the right branch (SDK work → that SDK's branch; everything else → `main`)
- [ ] One logical change — a refactor plus a fix would be two pull requests
- [ ] `ruff check .` and `ruff format --check .` are clean
- [ ] `mypy` is clean
- [ ] Nothing is described as implemented that is not implemented and tested
- [ ] No secrets, tokens, keys or `.env` files are included
- [ ] Commits follow [Conventional Commits](https://www.conventionalcommits.org/)
