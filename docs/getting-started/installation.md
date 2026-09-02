# Installation

All five SDKs implement the same execution API and pass the same
[conformance corpus](../../tests/conformance/cases.json). Pick the one that matches your project.

## Status at a glance

| Ecosystem | Package | Registry | Implemented | Published |
|---|---|---|---|---|
| Python | `kryon` | PyPI | ✅ | ✅ |
| TypeScript / JavaScript | `kryon` | npm | ✅ | Awaiting credentials |
| Dart | `kryon` | pub.dev | ✅ | Awaiting credentials |
| Java | `io.github.piyush-mishra-00:kryon` | Maven Central | ✅ | Awaiting credentials |
| Kotlin | `io.github.piyush-mishra-00:kryon-kotlin` | Maven Central | ✅ | Awaiting credentials |

"Awaiting credentials" means the package builds, tests and validates, and the release workflow is
configured — the registry account is a manual step that has not happened yet. It does **not** mean
the code is unfinished. See [releases](../development/releases.md) for exactly what remains.

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
npm install kryon
```

Node 20 or newer. **Zero runtime dependencies.** ESM-only, with generated declarations.

```ts
import { Runtime } from "kryon";
const result = await new Runtime({ encoding: "utf8" }).execute("git", ["--version"]);
```

For browsers, import `kryon/browser` — it carries the types and errors and no runtime, because a
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

## Package name availability

`kryon` was unclaimed on PyPI, npm and pub.dev when this project started, and Python has since
claimed it. The other three are still unclaimed as of writing — which means available at that
moment, not reserved. Names are claimed by publishing.

If one turns out to be taken by the time a release is ready, the conflict will be documented and a
coherent fallback chosen (a scope such as `@kryon/core`), never an unrelated word and never a
squat on something similar.
