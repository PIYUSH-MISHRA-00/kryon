# Kryon for Java

**Powerful terminal execution, everywhere.**

Run operating-system commands, stream their output, and manage the processes behind them —
with an API designed so the dangerous thing is the one you have to ask for by name.

[![Maven Central](https://img.shields.io/badge/maven--central-kryon-blue.svg)](https://central.sonatype.com/artifact/io.github.piyush-mishra-00/kryon)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/java-%E2%89%A517-orange.svg)](build.gradle.kts)

This is the Java SDK of [Kryon](https://github.com/PIYUSH-MISHRA-00/kryon). **Zero runtime
dependencies** — it orchestrates the JDK's own `ProcessBuilder` and nothing else.

> **`1.0.0`.** Command execution and process streaming are implemented and pass the
> [cross-language conformance corpus](tests/conformance/cases.json) on Linux, macOS and
> Windows. PTY, terminal emulation and remote transports are specified but **not implemented**.

## Install

Gradle:

```kotlin
implementation("io.github.piyush-mishra-00:kryon:1.0.0")
```

Maven:

```xml
<dependency>
  <groupId>io.github.piyush-mishra-00</groupId>
  <artifactId>kryon</artifactId>
  <version>1.0.0</version>
</dependency>
```

Requires Java 17 or newer.

> **On the coordinates.** The groupId is `io.github.piyush-mishra-00`, which is the namespace
> verifiable through GitHub account ownership. The *package* is `io.github.piyushmishra00.kryon`
> — hyphens are legal in a Maven groupId and illegal in a Java package identifier, so the two
> differ by exactly those characters.

## Run something

```java
import io.github.piyushmishra00.kryon.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

Runtime runtime = new Runtime(ExecutionOptions.builder()
        .charset(StandardCharsets.UTF_8)
        .timeout(Duration.ofSeconds(30))
        .build());

ExecutionResult result = runtime.execute("git", List.of("status", "--porcelain"));

System.out.println(result.stdout());
System.out.println(result.exitCode().orElseThrow() + " " + result.ok() + " " + result.duration());
```

## Talk to something

```java
try (KryonProcess proc = runtime.spawn("mvn", List.of("verify"))) {
    for (OutputChunk chunk : proc.output()) {
        var target = chunk.stream() == StreamKind.STDERR ? System.err : System.out;
        target.write(chunk.data());
        target.flush();
    }
    ExecutionResult result = proc.await();
    System.out.println("exit " + result.exitCode().orElseThrow());
}
```

`KryonProcess` is `AutoCloseable`. Leaving the try-with-resources block terminates the process
and closes every pipe, whether you left normally or by exception.

## Two things worth knowing

### Arguments are never interpreted

```java
runtime.execute("wc", List.of("-l", userInput));      // safe, whatever userInput is
runtime.executeShell("wc -l " + userInput);           // command injection
```

`execute` passes an argument vector to the operating system. No shell is involved, so nothing in
an argument can expand, glob, chain or substitute. Shell semantics live behind `executeShell` — a
**separate method name**, not a `shell(true)` builder call, because a boolean among a dozen
options is easy to set by accident and easy to miss in review.

This also rules out `Runtime.getRuntime().exec(String)`, which splits its argument on whitespace
and has surprised generations of Java developers.

### Kryon is not a sandbox

Timeouts and output caps manage resources. They do not contain a hostile program. Isolation is a
container, a VM, or an unprivileged account. See
[the threat model](docs/security/threat-model.md).

## API

### `new Runtime(ExecutionOptions defaults)`

Holds default options; safe to share across threads. Every call may override them with a third
argument. `env` merges with the runtime's `env`; everything else is replaced, and a boolean set
explicitly to `false` really does turn a default off.

| Builder method | Default | Meaning |
|---|---|---|
| `cwd(Path)` | inherited | Working directory. A path that is not a directory is an error, never a silent fallback. |
| `env(name, value)` | none | Merged over the inherited environment. A `null` value removes it. |
| `clearEnv(boolean)` | `false` | Start from an empty environment. With `env`, this is an allowlist. |
| `stdin(String \| byte[])` | none | Written to stdin, after which stdin is closed. |
| `timeout(Duration)` | none | On expiry: terminate, wait `killGrace`, kill. |
| `maxOutputBytes(long)` | none | Per-stream cap, enforced during the flood. |
| `charset(Charset)` | none | Used to decode `stdout()`/`stderr()`. |
| `check(boolean)` | `false` | Throw on an unsuccessful result. |
| `killGrace(Duration)` | `5s` | Between the polite stop and the forced kill. |

### `ExecutionResult`

`executable()`, `arguments()`, `exitCode()`, `signal()`, `stdout()`, `stderr()`,
`stdoutBytes()`, `stderrBytes()`, `duration()`, `termination()`, `pid()`, `stdoutTruncated()`,
`stderrTruncated()`, `ok()` and `checked()`.

`termination()` is `EXITED`, `SIGNALED`, `TIMEOUT`, `CANCELLED` or `OUTPUT_LIMIT`. The
Kryon-initiated reasons win over the kernel's account: a process killed for exceeding its timeout
reports `TIMEOUT`, because that is what you need in order to decide whether to retry.

### `KryonProcess`

`pid()`, `running()`, `exitCode()`, `write()`, `closeStdin()`, `output()`, `signal()`,
`terminate()`, `kill()`, `await()`, `close()`.

`output()` is a one-shot `Iterable<OutputChunk>` drained from a bounded queue. Stop consuming and
Kryon stops reading, so the child blocks instead of your heap growing.

### Errors

The rule: **failing to start is an error, failing while running is a result.**

`CommandNotFoundException`, `PermissionDeniedException`, `ProcessStartFailedException` and
`InvalidArgumentsException` are thrown — no process ran. `ProcessFailedException`,
`ProcessTimeoutException`, `ProcessCancelledException` and `ResourceLimitExceededException` are
thrown only under `check(true)`, and each carries the `ExecutionResult` it came from via
`result()`. All extend `KryonException`, which extends `RuntimeException`: a checked exception on
every `execute` call would push callers towards catching and ignoring, which is worse.

### A naming note

The output-stream enum is `StreamKind`, not `Stream` or `OutputStream`. Both of those collide
with types practically every Java file already imports, and a Kryon type sharing their simple name
would force callers to fully qualify one of them forever.

## Platform notes

| | Linux | macOS | Windows |
|---|---|---|---|
| `execute` / `spawn` | Yes | Yes | Yes |
| `signal(int)` | `SIGTERM`/`SIGKILL` only | Same | `UnsupportedPlatformException` |
| `terminate()` | `SIGTERM` | `SIGTERM` | `TerminateProcess` — no graceful stop |
| `result.signal()` | Derived, see below | Derived | Always empty |

Two JVM-specific limitations, stated rather than hidden:

**`signal(int)` supports only 15 and 9.** The JDK's `Process` API exposes `destroy()` and
`destroyForcibly()` and nothing else. Sending `SIGHUP` or `SIGUSR1` would need a native call this
library deliberately does not make.

**`signal()` on a result is inferred, not read.** The JVM reports a signal death as
`128 + signum` and gives no signal number of its own, so Kryon derives it from the exit value. A
program that genuinely exits `143` is indistinguishable from one killed by `SIGTERM`. This is a
JDK limitation; the Python and Dart SDKs report the real signal.

## Develop

```bash
cd java
./gradlew build          # compile, javadoc, sources jar, tests
./gradlew test
./gradlew publishToMavenLocal
```

The compiler runs with `-Xlint:all -Werror`, so a warning fails the build. Tests drive a small
[helper program](java/src/test/java/io/github/piyushmishra00/kryon/ConformanceHelper.java) rather than
real system commands, so they behave the same on every platform.

## License

Apache-2.0. See [LICENSE](LICENSE).

---

If Kryon saves you time, you can
[buy me a coffee](https://buymeacoffee.com/piyushmishra00).
