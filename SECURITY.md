# Security Policy

Kryon executes arbitrary system commands. Security is a feature of this project, not a
section of its documentation.

## Reporting a vulnerability

**Use GitHub's private vulnerability reporting:**
**[Report a vulnerability](https://github.com/PIYUSH-MISHRA-00/kryon/security/advisories/new)**

Do **not** open a public issue, discussion or pull request for an undisclosed vulnerability.

Include what you can:

- what the issue is, and which component
- how to reproduce it, ideally minimally
- affected versions and platforms
- what an attacker gains
- a suggested fix, if you have one

### What to expect

| | |
|---|---|
| Acknowledgement | Within 72 hours |
| Initial assessment | Within 7 days |
| Fix or mitigation plan | Within 30 days for a confirmed issue |
| Public advisory | After a fix ships, or 90 days, whichever comes first |

Kryon is currently maintained by one person. These are honest targets, not a commercial SLA.
If a deadline slips you will be told, not ignored.

You will be credited in the advisory unless you prefer otherwise. There is no bug bounty.

## Supported versions

| Version | Supported |
|---|---|
| `0.1.x` | ✅ Current |
| `< 0.1` | ❌ No releases exist |

While `0.x`, only the latest minor version receives security fixes. Backports begin at `1.0`.

## Scope

### In scope

- Command injection through any API documented as safe — above all, `execute()` treating an
  argument as anything other than a literal argument
- A process surviving a documented termination path (timeout, cancellation, scope exit,
  `terminate()`)
- Credentials appearing in an error message, exception, or anything Kryon writes
- An output or timeout limit failing to bound what it claims to bound
- Environment controls (`clear_env`, `env`) failing to remove what they claim to remove
- Any documentation, example or website copy that implies a security property Kryon does not
  have — this is a real bug and will be treated as one
- Supply-chain issues in the published packages

### Out of scope

These are documented behaviours, not vulnerabilities:

- **`execute_shell` running shell commands.** That is what it is for. Interpolating untrusted
  input into it is a vulnerability *in the calling application*.
- **Kryon not sandboxing a child process.** Kryon is not a sandbox and says so everywhere.
  See [sandboxing](docs/security/sandboxing.md).
- **A child process escaping a timeout by forking and detaching.** Process-tree termination
  is not implemented and is documented as not implemented.
- **`terminate()` not being graceful on Windows.** Windows has no `SIGTERM`. This is
  documented in the README, the specification, the SDK docs and the platform matrix.
- **An application exposing Kryon to a network without authentication.** See
  [remote execution](docs/security/remote-execution.md); the requirements are written down.

If you are unsure which side of the line something falls on, report it privately. A
misclassified report is a much smaller problem than an unreported one.

## Threat model

The full model — what Kryon defends against, what it explicitly does not, and the attacker
scenarios — is in [`docs/security/threat-model.md`](docs/security/threat-model.md).

The short version:

> Kryon is a well-behaved way to run programs you already have the right to run. It is not a
> way to safely run programs you do not trust.

## For users of Kryon

1. **Never build an `execute_shell` string from untrusted input.**
2. **Never let untrusted input choose the executable.** The argument vector is irrelevant if
   the attacker picks the program.
3. **Clear the environment** for anything you did not write: `clear_env=True` plus an `env`
   allowlist.
4. **Set a timeout and an output cap.** They keep your process alive; they do not contain a
   hostile one.
5. **Isolate at the operating system** for anything untrusted — container, VM, unprivileged
   user. [Sandboxing](docs/security/sandboxing.md).
6. **Never expose execution to a browser** without authentication, authorization, a command
   allowlist and isolation, [all four](docs/security/remote-execution.md).

## Project security practices

- **No runtime dependencies** in the Python SDK. Nothing to compromise upstream.
- **No network calls.** No telemetry, no analytics, no update check, no crash reporting, and
  no configuration that could enable any of them.
- **No secrets in the repository.** Enforced by `.gitignore` and checked before release.
- **Trusted Publishing** for PyPI when releases begin — an OIDC assertion from this
  repository's workflow, so no long-lived token exists to steal.
- **Dependabot** on GitHub Actions and each ecosystem's manifests.
- **Signed release tags.**
- **Security tests are conformance cases**, so a regression fails five SDKs' test suites at
  once rather than being noticed by nobody.
