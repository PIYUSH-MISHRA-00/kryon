# Installation

All five SDKs implement the same execution API and pass the same
[conformance corpus](../../tests/conformance/cases.json). Pick the one that matches your project.

## Status at a glance

| Ecosystem | Package | Registry | Implemented | Published |
|---|---|---|---|---|
| Python | `kryon` | PyPI | ✅ | ✅ |
| TypeScript / JavaScript | `kryon-exec` | npm | ✅ | ✅ |
| Dart | `kryon` | pub.dev | ✅ | ✅ |
| Java | `io.github.piyush-mishra-00:kryon` | Maven Central | ✅ | ✅ |
| Kotlin | `io.github.piyush-mishra-00:kryon-kotlin` | Maven Central | ✅ | ✅ |

All five are published at 1.0.0. The two Maven Central artifacts are GPG-signed with key
`704CD5A4984CD865`, published to keyserver.ubuntu.com and keys.openpgp.org; every `.jar`,
`.pom`, sources and javadoc has a `.asc` beside it in the repository, so you can verify the
download rather than take this table's word for it. See
[releases](../development/releases.md) for how publishing works.

## Python

```bash
pip install kryon
```

Python 3.9 or newer, on Linux, macOS or Windows. **Zero runtime dependencies.**

```python
import kryon
print(kryon.__version__)
print(kryon.Runtime().execute("git", ["--version"], encoding="utf-8").stdout)
```

## TypeScript / JavaScript

```bash
npm install kryon-exec
```

Node 20 or newer. **Zero runtime dependencies.** ESM-only, with generated declarations.

```ts
import { Runtime } from "kryon-exec";
const result = await new Runtime({ encoding: "utf8" }).execute("git", ["--version"]);
```

For browsers, import `kryon-exec/browser` — it carries the types and errors and no runtime, because a
browser cannot execute host commands. See [remote execution](../security/remote-execution.md).

## Dart

```bash
dart pub add kryon
```

Dart 3.0 or newer. **Zero runtime dependencies.**

```dart
import 'package:kryon/kryon.dart';
final result = await Runtime().execute('git', ['--version']);
```

## Java

```kotlin
// Gradle
implementation("io.github.piyush-mishra-00:kryon:1.0.0")
```

```xml
<!-- Maven -->
<dependency>
  <groupId>io.github.piyush-mishra-00</groupId>
  <artifactId>kryon</artifactId>
  <version>1.0.0</version>
</dependency>
```

Java 17 or newer. **Zero runtime dependencies.**

## Kotlin

```kotlin
implementation("io.github.piyush-mishra-00:kryon-kotlin:1.0.0")
```

Java 17 or newer. One dependency: `kotlinx-coroutines-core`.

The Kotlin SDK is a native implementation, not a wrapper over the Java one, and lives in a
different package (`io.github.piyushmishra00.kryon.coroutines`) so the two jars can coexist on a
classpath.

## On the Maven coordinates

The groupId is `io.github.piyush-mishra-00` because that namespace is verifiable through GitHub
account ownership — Kryon does not own a domain and will not claim one it does not have.

The *Java package* drops the hyphens (`io.github.piyushmishra00.kryon`), because hyphens are legal
in a Maven groupId and illegal in a Java package identifier. The two differing by exactly those
characters is the standard way to reconcile a hyphenated username with the language.

## From source

```bash
git clone https://github.com/PIYUSH-MISHRA-00/kryon.git
cd kryon

cd python     && pip install -e ".[dev]" && pytest
cd ../javascript && npm install && npm test
cd ../dart    && dart pub get && dart test
cd ../java    && ./gradlew test
cd ../kotlin  && ./gradlew test
```

You only need the toolchain for the SDK you are working on. See
[development setup](../development/setup.md).

## Package names, and the one conflict

`kryon` on PyPI. `kryon` on pub.dev. **`kryon-exec` on npm.**

The npm registry refuses the name `kryon` outright, with
`403 — Package name too similar to existing package cron`. That is npm's typosquatting filter,
not a name clash: nobody holds `kryon`, and nobody can publish it.

`kryon-exec` was chosen over the alternatives (`@kryon/core`, `@piyush-mishra-00/kryon`) because
it stays unscoped and short, and it still reads as this project. It is the same code, the same
version number and the same conformance corpus as every other SDK — only the registry name
differs, and only on npm.

Maven Central uses `io.github.piyush-mishra-00` for both JVM artifacts, because that namespace is
verifiable through GitHub account ownership and needs no domain.
