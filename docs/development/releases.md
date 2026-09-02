# Releases

## Versioning

[Semantic Versioning](https://semver.org/). All SDKs share one version number, so that
"Kryon 0.3" means the same set of capabilities in every language.

While `0.x`:

- Minor bumps (`0.1` → `0.2`) may contain breaking changes. They will be documented in
  [`CHANGELOG.md`](../../CHANGELOG.md) with a migration note.
- Patch bumps are fixes only.
- Nothing is described as stable. It is not.

`1.0.0` is reached when the public API has survived real use, PTY works on all three desktop
platforms, at least three SDKs pass the full conformance corpus, and there is a written
compatibility promise worth making. Not before. See [`ROADMAP.md`](../../ROADMAP.md).

## What has to be true before a release

- [ ] Every SDK's test suite passes on every supported platform in CI
- [ ] The conformance corpus passes, with every skip carrying a reason
- [ ] Lint, format and type checks are clean
- [ ] `CHANGELOG.md` is updated, with migration notes for anything breaking
- [ ] Version numbers match across every package manifest
- [ ] The [status table](../../spec/README.md#implementation-status) reflects reality
- [ ] READMEs claim nothing that is not implemented
- [ ] Packages build and pass their registry's validation
- [ ] No secrets anywhere in the tree

## Cutting one

```bash
# 1. Update version and changelog on main
#    python/src/kryon/__init__.py   __version__ = "0.2.0"
#    CHANGELOG.md

git commit -am "chore: release 0.2.0"

# 2. Tag
git tag -a v0.2.0 -m "Kryon 0.2.0"
git push origin main --follow-tags
```

Tags are `vMAJOR.MINOR.PATCH` and are cut from `main` only. Pushing the tag is what triggers
the release workflow.

## Publishing

Each registry needs credentials the project does not have and will not fabricate.

| Registry | Package | Credential | Status |
|---|---|---|---|
| PyPI | `kryon` | Trusted Publishing (OIDC) | **Not configured** |
| npm | `kryon` | `NPM_TOKEN` + provenance | Not applicable yet |
| pub.dev | `kryon` | OIDC via GitHub Actions | Not applicable yet |
| Maven Central | `io.github.piyush-mishra-00:kryon` | Portal token + GPG signing key | Not applicable yet |

**PyPI uses Trusted Publishing**, which means no long-lived token exists at all: PyPI is
configured to accept a signed OIDC assertion from this specific repository and workflow. That
removes the single most common way a maintainer account gets compromised. Configuring it is a
manual step in PyPI's web interface and cannot be automated from here.

Rules that are not negotiable:

- Tokens live in GitHub Actions secrets or an environment, never in the repository, never in
  a workflow file, never in a README, never in a shell history.
- Signing keys are never committed.
- Nothing is published until it builds, tests and passes registry validation.
- Nothing is described as "published" until it actually is.

## Package names

`kryon` returned "not found" on PyPI, npm and pub.dev when this was written — unclaimed at
that moment, not reserved. Names are claimed by publishing, and Kryon has published nothing.

If a name turns out to be taken, the conflict gets documented and a coherent fallback chosen
— a scope (`@kryon/core`) or a suffix that still reads as this project. Never an unrelated
word, and never a squat on a similar name.

Maven coordinates use `io.github.piyush-mishra-00` because that namespace is verifiable
through GitHub account ownership. Kryon does not own a domain and will not claim one it does
not have.

## GitHub releases

Every tag gets a release with the changelog section, migration notes for breaking changes,
and links to the published packages once they exist. No fabricated artifacts, and no release
notes for a release that did not happen.

## Yanking

If a release is broken badly enough to hurt someone:

1. Yank it on the registry (PyPI yank, npm deprecate). Do not delete — deletion breaks
   lockfiles for everyone who already installed it.
2. Publish a fixed patch version.
3. Note both in the changelog, with what went wrong.

If it was a security issue, publish an advisory as well. See [`SECURITY.md`](../../SECURITY.md).
