# Governance

Kryon is a young project with one maintainer. This document describes what is actually true
today and how it is expected to change, rather than describing an organisation that does not
exist.

## Today

**Project lead and sole maintainer:** [@PIYUSH-MISHRA-00](https://github.com/PIYUSH-MISHRA-00)

Which means, honestly:

- One person reviews and merges everything.
- One person decides what goes in the specification.
- One person cuts releases.
- One person answers security reports.
- If that person is unavailable, the project stalls.

That last point is a real risk, not a formality. It is the main reason to want more
maintainers.

## How decisions are made

**Ordinary changes** — bug fixes, documentation, tests, non-behavioural improvements — are
decided in the pull request.

**Behavioural changes** — anything that changes what an SDK observably does — change the
[specification](spec/) first, in their own pull request, with the reasoning written down.
The specification is the source of truth precisely so that decisions survive the memory of
whoever made them.

**Architectural changes** — a new layer, a new extension point, a dependency, a new SDK —
start as a [discussion](https://github.com/PIYUSH-MISHRA-00/kryon/discussions) before code
is written.

**Security decisions** are the maintainer's call and are not subject to a popularity
argument. A feature that makes the dangerous path more convenient will be declined however
well argued. The reasons are in the [threat model](docs/security/threat-model.md).

## Principles that constrain the maintainer too

These are not preferences; they are the terms on which the project is worth using.

1. **Correctness over features.** A small correct runtime beats a large collection of
   half-working abstractions.
2. **Security is not a section.** This project executes arbitrary commands. Convenience never
   wins over a security property.
3. **No overstated claims.** Nothing is described as implemented until it is implemented and
   tested. No fabricated badges, adoption numbers, testimonials or benchmark figures.
4. **Specification before implementation.** For anything cross-language.
5. **Dependencies are a liability.** Especially in a package that runs arbitrary programs.
6. **No hidden behaviour.** No telemetry, no phoning home, no implicit shell, no ambient
   global state.
7. **Respect other projects.** Comparisons compare architecture and use case, never quality.
   Nobody's project gets disparaged to make this one look better.

A pull request from the maintainer that violates one of these is as wrong as one from anyone
else. They are written down so that they can be pointed at.

## Becoming a maintainer

There is no committee and no application form. The path is:

1. Contribute consistently and well over time.
2. Show judgement in reviews — including disagreeing with the maintainer when the reasoning
   is better.
3. Take ownership of an area: an SDK, the specification, the website, CI.
4. Be invited.

Commit access will be offered to anyone who reaches that point, particularly someone who
implements and maintains an SDK. A second SDK maintained by a second person is what turns
this from one person's project into a project.

## How this evolves

| If | Then |
|---|---|
| A second regular maintainer appears | Shared review, two-person sign-off for specification changes |
| Multiple SDKs have maintainers | Per-SDK ownership; cross-cutting decisions need consensus |
| The project grows further | A written technical steering process, replacing this document |
| The maintainer becomes unavailable | Documented handover — see below |

## Succession

If the maintainer becomes unavailable for an extended period, the project should be handed to
active maintainers, or archived clearly rather than left to rot with an unmaintained package
on a registry.

There is currently no second person with publishing rights. That is a known single point of
failure and will be addressed when there is a second maintainer to address it with. Saying so
plainly is more useful than a governance document that implies redundancy that is not there.

## Code of Conduct

The [Code of Conduct](CODE_OF_CONDUCT.md) applies to everyone, maintainer included. Reports
about the maintainer's own conduct are a genuine problem in a single-maintainer project; if
that happens, raise it publicly in an issue or discussion, where it cannot quietly be
dropped.
