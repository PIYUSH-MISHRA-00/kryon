# Releases

## Versioning

[Semantic Versioning](https://semver.org/). All five SDKs share one version number, so that
"Kryon 1.0" means the same set of capabilities in every language. `scripts/check_versions.py`
enforces that in CI: eight version declarations across five packages must agree, or the build
fails.

Since `1.0.0`, the [stable areas of the specification](../../spec/README.md#implementation-status)
— execution, process streaming and the error taxonomy — carry a compatibility promise. Breaking
them requires a major version. The full statement of what is and is not promised is in
[`ROADMAP.md`](../../ROADMAP.md#what-100-promises).

## What has to be true before a release

- [ ] Every SDK's test suite passes on Linux, macOS and Windows in CI
- [ ] The conformance corpus passes in all five, with every skip carrying a reason
- [ ] Lint, format and type checks are clean in every SDK
- [ ] `scripts/check_versions.py` passes — all eight declarations agree
- [ ] `CHANGELOG.md` has a section for the version, with migration notes for anything breaking
- [ ] The [status table](../../spec/README.md#implementation-status) reflects reality
- [ ] READMEs claim nothing that is not implemented
- [ ] Packages build and pass each registry's validation
- [ ] No secrets anywhere in the tree

## Cutting one

```bash
# 1. Bump every version. All eight of them:
#      python/src/kryon/__init__.py    __version__
#      javascript/package.json         version
#      javascript/src/index.ts         VERSION
#      javascript/src/browser.ts       VERSION
#      dart/pubspec.yaml               version
#      dart/lib/kryon.dart             kryonVersion
#      java/gradle.properties          version
#      kotlin/gradle.properties        version
python scripts/check_versions.py       # confirms they agree

# 2. Write the changelog section.

git commit -am "chore: release 1.1.0"

# 3. Tag. This is what triggers the release workflow.
git tag -a v1.1.0 -m "Kryon 1.1.0"
git push origin main --follow-tags
```

Tags are `vMAJOR.MINOR.PATCH` and are cut from `main` only. The workflow refuses a tag whose
version does not match the packages, or for which the changelog has no section.

## Publishing

Every publish job sits behind a GitHub environment, so a release cannot reach a registry without
a human approving it, and the credentials live there rather than in the workflow file.

| Registry | Package | Credential | Status |
|---|---|---|---|
| PyPI | `kryon` | Trusted Publishing (OIDC) | ✅ Configured and published |
| npm | `kryon-exec` | `NPM_TOKEN`, or Trusted Publishing | ✅ Published |
| pub.dev | `kryon` | OIDC, after a manual first publish | ✅ Published |
| Maven Central | `io.github.piyush-mishra-00:kryon` | GPG key (local agent) | ✅ Published |
| Maven Central | `…:kryon-kotlin` | Same | ✅ Published |

### PyPI

Uses **Trusted Publishing**: PyPI verifies a signed OIDC assertion from this repository and
workflow, so no long-lived token exists to steal. Already set up. Nothing to do per release beyond
approving the `pypi` environment.

### npm

1. Create a **granular access token** on npmjs.com scoped to the `kryon` package, read-and-write.
2. Add it as the repository secret `NPM_TOKEN`.
3. Create a GitHub environment called `npm` with yourself as a required reviewer.

The workflow publishes with `--provenance`, which attaches a signed attestation linking the
tarball to the exact commit and workflow that built it. If you later configure npm's own Trusted
Publishing for the package, the token becomes unnecessary and the `NODE_AUTH_TOKEN` line in
`release.yml` can be deleted.

### pub.dev

The **first publish must be interactive**, because automated publishing is configured on a
package's admin page and that page does not exist until the package does:

```bash
cd dart
dart pub publish --dry-run     # fix everything it reports first
dart pub publish
```

After that, enable automated publishing on the pub.dev admin page — point it at
`PIYUSH-MISHRA-00/kryon` with tag pattern `v{{version}}`. It uses OIDC, so there is no token.
Then create a GitHub environment called `pub-dev`.

pub.dev scores packages on analysis, documentation and example quality, which is why `dart analyze`
must be clean and `example/` exists.

### Maven Central

The most involved of the four, and the only one still requiring a long-lived credential.

1. **Register the namespace.** At [central.sonatype.com](https://central.sonatype.com), claim
   `io.github.piyush-mishra-00`. Verification is proving GitHub account ownership — usually by
   creating a public repository with a name they give you. No domain needed, which is exactly why
   this namespace was chosen.
2. **Create a GPG key** and publish the public half:
   ```bash
   gpg --gen-key
   gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
   gpg --export-secret-keys --armor <KEY_ID> | base64 -w0
   ```
3. **Generate a user token** in the Portal (Account → Generate User Token).
4. **Create a GitHub environment** called `maven-central`, with yourself as a required reviewer,
   and add three secrets to it: `SIGNING_KEY` (the base64 blob), `SIGNING_PASSWORD`, and
   `CENTRAL_PORTAL_TOKEN`.

The workflow builds, signs, verifies signatures exist, zips the bundle and uploads it as
`USER_MANAGED` — which means it is *staged*, not released. You then review and release it at
[central.sonatype.com/publishing/deployments](https://central.sonatype.com/publishing/deployments).
That extra step is deliberate: a Maven Central release is permanent and cannot be unpublished.

The Gradle build only signs when `SIGNING_KEY` is present, and the workflow **fails** if the
bundle contains no signatures. A build that silently produced unsigned artifacts and called them
releasable would be worse than one that stops.

## Rules that are not negotiable

- Tokens and signing keys live in GitHub environments, never in the repository, never in a
  workflow file, never in a README, never in shell history.
- Prefer OIDC over long-lived tokens wherever the registry offers it.
- Nothing is published until it builds, tests and passes registry validation.
- Nothing is described as "published" until it actually is. All five say published because
  all five are, and each one was checked by installing or downloading it afterwards — not by
  trusting the workflow's exit code.

## GitHub releases

Every tag gets a draft release carrying the changelog section and the built artifacts — wheels,
sdists, npm tarballs and jars. Review it, then publish. No fabricated artifacts, and no release
notes for a release that did not happen.

## Yanking

If a release is broken badly enough to hurt someone:

1. Yank it on the registry (PyPI yank, `npm deprecate`, pub.dev retract). Do not delete — deletion
   breaks lockfiles for everyone who already installed it. **Maven Central cannot be unpublished
   at all**, which is why its release step is manual.
2. Publish a fixed patch version.
3. Note both in the changelog, with what went wrong.

If it was a security issue, publish an advisory as well. See [`SECURITY.md`](../../SECURITY.md).
