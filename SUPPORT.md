# Support

## Where to go

| I want to… | Go to |
|---|---|
| Ask how to do something | [Discussions → Q&A](https://github.com/PIYUSH-MISHRA-00/kryon/discussions) |
| Report a bug | [Issues → Bug report](https://github.com/PIYUSH-MISHRA-00/kryon/issues/new/choose) |
| Propose a feature | [Issues → Feature request](https://github.com/PIYUSH-MISHRA-00/kryon/issues/new/choose) |
| Report a security vulnerability | [Private advisory](https://github.com/PIYUSH-MISHRA-00/kryon/security/advisories/new) — **never a public issue** |
| Fix the documentation | A pull request, or [Issues → Documentation](https://github.com/PIYUSH-MISHRA-00/kryon/issues/new/choose) |
| Share what you built | [Discussions → Show and tell](https://github.com/PIYUSH-MISHRA-00/kryon/discussions) |
| Offer to implement an SDK | [Discussions](https://github.com/PIYUSH-MISHRA-00/kryon/discussions) first, so work is not duplicated |

## Before you ask

Most questions are already answered:

- [Your first commands](docs/getting-started/first-terminal.md) — the common cases
- [Platform support](docs/guides/platform-support.md) — "why is Windows different?"
- [Threat model](docs/security/threat-model.md) — "is this safe?"
- [Why Kryon?](docs/guides/why-kryon.md) — "should I use this instead of X?"
- [Specification](spec/README.md) — "what is this *supposed* to do?"
- [Roadmap](ROADMAP.md) — "when will X exist?"

## Things that come up often

**"My streaming output only arrives when the process exits."** The child is buffering. Most
programs buffer their output when they detect they are not attached to a terminal. Run it
unbuffered if you can (`python -u`, `stdbuf -o0`), or wait for PTY support — a real
pseudo-terminal makes programs line-buffer, and that is exactly what it is for. Kryon cannot
change a program's buffering from the outside.

**"`terminate()` does not let my process clean up on Windows."** Correct, and unavoidable.
Windows has no `SIGTERM`; `terminate()` there calls `TerminateProcess`, which stops the
process immediately. See [platform support](docs/guides/platform-support.md#windows-differences-that-will-bite-you).

**"The child process survived my timeout."** If it forked and detached, that is documented:
process-tree termination is not implemented. If it did *not* fork, that is a bug — please
report it.

**"Why is there no `shell=True`?"** Deliberate. Use `execute_shell`, and read
[command execution](docs/security/command-execution.md) first.

**"Is Kryon safe to expose to the internet?"** Not on its own. See
[remote execution](docs/security/remote-execution.md).

## Writing a good bug report

The [template](.github/ISSUE_TEMPLATE/bug_report.yml) asks for these because they are what
makes a report actionable:

- Kryon version, SDK, language version, operating system
- A minimal reproduction — the smallest program that shows it
- What you expected and what happened
- The full error, if there was one

For anything involving process lifetime or timing, mention whether it reproduces every time.
Intermittent process bugs are the ones worth chasing hardest, and knowing it is intermittent
is half the information.

## Response times

Kryon is maintained by one person, in their own time.

| | Typical |
|---|---|
| Security reports | Within 72 hours — these come first, always |
| Bug reports | A few days |
| Feature requests | Longer; a decision may take a while |
| Pull requests | A few days for a first look |

If something has gone quiet for two weeks, a polite comment on the thread is welcome and not
an imposition.

## What is not offered

There is no commercial support, no SLA, no private support channel, no consulting. Kryon is
an open-source project, not a product with a support contract behind it.

## Code of Conduct

The [Code of Conduct](CODE_OF_CONDUCT.md) applies everywhere in this project — issues,
discussions, pull requests and reviews.
