# Kryon Documentation

**Powerful terminal execution, everywhere.**

Kryon runs operating-system commands and manages the processes behind them, with one
conceptual API being implemented across five language ecosystems.

> **Project status: `1.0.0`.** All five SDKs -- Python, TypeScript, Dart, Java and Kotlin --
> implement command execution and process streaming, and all five pass the same conformance
> corpus. PTY, terminal emulation and remote transports are specified and **not implemented**.
> Nothing here describes a feature that does not exist; where something is planned, it says so.

## Start here

| | |
|---|---|
| [**Overview**](getting-started/overview.md) | What Kryon is, what it is not, and why it exists |
| [**Installation**](getting-started/installation.md) | Getting it, per ecosystem |
| [**Your first commands**](getting-started/first-terminal.md) | From one command to a live streaming process |

## Understand it

| | |
|---|---|
| [**Architecture overview**](architecture/overview.md) | The layers and why they are separate |
| [**SDK design**](architecture/sdk-design.md) | How five SDKs stay one product |
| [**Why Kryon?**](guides/why-kryon.md) | Honest comparison with `subprocess`, PTY libraries and terminal components |
| [**Platform support**](guides/platform-support.md) | The compatibility matrix, with the differences spelled out |

## Read this before shipping it

| | |
|---|---|
| [**Threat model**](security/threat-model.md) | What Kryon protects against, and what it explicitly does not |
| [**Command execution**](security/command-execution.md) | Argument vectors versus shell strings |
| [**Remote execution**](security/remote-execution.md) | The only safe shape for a browser-facing terminal |
| [**Sandboxing**](security/sandboxing.md) | What actually isolates a process — none of it is Kryon |

## Build on it

| | |
|---|---|
| [**Specification**](../spec/README.md) | The normative, language-neutral contract |
| [**Conformance corpus**](../tests/conformance/cases.json) | The shared test corpus every SDK must pass |
| [**Python**](../python/README.md) · [**TypeScript**](../javascript/README.md) · [**Dart**](../dart/README.md) · [**Java**](../java/README.md) · [**Kotlin**](../kotlin/README.md) | Per-SDK API reference |

## Contribute

| | |
|---|---|
| [**Development setup**](development/setup.md) | Toolchains, install, test, lint |
| [**Repository structure**](development/repository-structure.md) | What lives where, and why |
| [**Branching model**](development/branching.md) | `main` plus one branch per language |
| [**Testing**](development/testing.md) | Unit, conformance, platform |
| [**Releases**](development/releases.md) | Versioning, tags, publishing |
| [**Contributing**](../CONTRIBUTING.md) | Everything else |

## A note on the shape of these docs

The [specification](../spec/README.md) is normative and answers *what must happen*. These
documents are explanatory and answer *why it happens that way*. Where the two seem to
disagree, the specification wins and the documentation has a bug.
