# Kryon for Kotlin

**Powerful terminal execution, everywhere.**

Run operating-system commands, stream their output, and manage the processes behind them —
with coroutines, structured cancellation, and an API designed so the dangerous thing is the one
you have to ask for by name.

[![Maven Central](https://img.shields.io/badge/maven--central-kryon--kotlin-blue.svg)](https://central.sonatype.com/artifact/io.github.piyush-mishra-00/kryon-kotlin)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/kotlin-2.0-7F52FF.svg)](build.gradle.kts)

This is the Kotlin SDK of [Kryon](https://github.com/PIYUSH-MISHRA-00/kryon). It is a native
Kotlin implementation, not a wrapper over the Java SDK.

> **`1.0.0`.** Command execution and process streaming are implemented and pass the
> [cross-language conformance corpus](tests/conformance/cases.json) on Linux, macOS and
> Windows. PTY, terminal emulation and remote transports are specified but **not implemented**.

## Install

```kotlin
implementation("io.github.piyush-mishra-00:kryon-kotlin:1.0.0")
```

Requires Java 17 or newer. The one dependency is `kotlinx-coroutines-core`, and it earns its
place: coroutines are how asynchronous Kotlin is written, and reimplementing structured
concurrency to avoid a dependency would be strictly worse than using it.

## Run something

```kotlin
import io.github.piyushmishra00.kryon.coroutines.*
import kotlin.time.Duration.Companion.seconds

val runtime = Runtime(ExecutionOptions(charset = Charsets.UTF_8, timeout = 30.seconds))

val result = runtime.execute("git", listOf("status", "--porcelain"))

println(result.stdout)
println("${result.exitCode} ${result.ok} ${result.duration}")
```

## Talk to something

```kotlin
runtime.spawn("gradle", listOf("build")).use { proc ->
    proc.output.collect { chunk ->
        print(chunk.text())
    }
    val result = proc.await()
    println("exit ${result.exitCode}")
}
```

`use` is the suspending version and closes the process whatever happens — normal return,
exception, or cancellation.

## Cancellation is structural

```kotlin
coroutineScope {
    val job = launch { runtime.execute("./forever") }
    delay(1000)
    job.cancel()          // the child is gone before the CancellationException propagates
}
```

Cancelling the coroutine terminates the child process first. Kryon never resumes a caller while
leaving a process running — including through the blocking `Process.waitFor` underneath, which is
run under `runInterruptible` precisely so that cancellation actually reaches it.

## Two things worth knowing

### Arguments are never interpreted

```kotlin
runtime.execute("wc", listOf("-l", userInput))     // safe, whatever userInput is
runtime.executeShell("wc -l $userInput")           // command injection
```

`execute` passes an argument vector to the operating system. No shell is involved, so nothing in
an argument can expand, glob, chain or substitute. Shell semantics live behind `executeShell` — a
**separate function name**, not a `shell = true` flag, because a boolean among a dozen named
arguments is easy to set by accident and easy to miss in review.

### Kryon is not a sandbox

Timeouts and output caps manage resources. They do not contain a hostile program. Isolation is a
container, a VM, or an unprivileged account. See
[the threat model](docs/security/threat-model.md).

## API

### `Runtime(defaults: ExecutionOptions = ExecutionOptions())`

Holds default options; safe to share. Every call may override them with an `options` argument.
`env` merges with the runtime's `env`; everything else is replaced. Every option is nullable, so
"not specified" stays distinguishable from "specified as false" — without that, a per-call
override could turn a flag on but never off.

| Option | Default | Meaning |
|---|---|---|
| `cwd` | inherited | Working directory. A path that is not a directory is an error, never a silent fallback. |
| `env` | `emptyMap()` | Merged over the inherited environment. A `null` value removes one. |
| `clearEnv` | `false` | Start from an empty environment. With `env`, this is an allowlist. |
| `stdin` | `null` | Written to stdin, after which stdin is closed. |
| `timeout` | `null` | A `kotlin.time.Duration`. On expiry: terminate, wait `killGrace`, kill. |
| `maxOutputBytes` | `null` | Per-stream cap, enforced during the flood. |
| `charset` | `null` | Set it for text output, leave it for bytes. |
| `check` | `false` | Throw on an unsuccessful result. |
| `killGrace` | `5.seconds` | Between the polite stop and the forced kill. |

### `ExecutionResult`

`executable`, `arguments`, `exitCode`, `signal`, `stdout`, `stderr`, `stdoutRaw`, `stderrRaw`,
`duration`, `termination`, `pid`, `stdoutTruncated`, `stderrTruncated`, `ok` and `checked()`.

`termination` is `EXITED`, `SIGNALED`, `TIMEOUT`, `CANCELLED` or `OUTPUT_LIMIT`. The
Kryon-initiated reasons win over the kernel's account: a process killed for exceeding its timeout
reports `TIMEOUT`, because that is what you need in order to decide whether to retry.

### `KryonProcess`

`pid`, `running`, `exitCode`, `write()`, `closeStdin()`, `output`, `signal()`, `terminate()`,
`kill()`, `await()`, `closeAndJoin()`, `use { }`.

`output` is a `Flow<OutputChunk>` backed by a bounded channel. Stop collecting and Kryon stops
reading, so the child blocks instead of your heap growing. There is one collector — collecting
twice throws, because the second would silently steal chunks from the first.

### Errors

The rule: **failing to start is an error, failing while running is a result.**

`CommandNotFoundException`, `PermissionDeniedException`, `ProcessStartFailedException` and
`InvalidArgumentsException` throw — no process ran. `ProcessFailedException`,
`ProcessTimeoutException`, `ProcessCancelledException` and `ResourceLimitExceededException` throw
only under `check = true`, and each carries the `ExecutionResult` it came from. All extend
`KryonException`.

## Relationship to the Java SDK

They are separate artifacts with separate packages:

| | Java | Kotlin |
|---|---|---|
| Artifact | `io.github.piyush-mishra-00:kryon` | `io.github.piyush-mishra-00:kryon-kotlin` |
| Package | `io.github.piyushmishra00.kryon` | `io.github.piyushmishra00.kryon.coroutines` |
| Async | Blocking, plus `AutoCloseable` | `suspend`, `Flow`, structured cancellation |
| Dependencies | None | `kotlinx-coroutines-core` |

The packages differ on purpose: two jars sharing a package would collide on the classpath of any
project that happened to use both. Using the Kotlin SDK does **not** pull in the Java one.

## Platform notes

Identical to the Java SDK, including the two JVM limitations:

- **`signal(Int)` supports only 15 and 9.** The JDK's `Process` API exposes `destroy()` and
  `destroyForcibly()` and nothing else.
- **`signal` on a result is inferred, not read.** The JVM reports a signal death as
  `128 + signum`, so a program that genuinely exits `143` is indistinguishable from one killed by
  `SIGTERM`.

Windows has no `SIGTERM`: `terminate()` there is the same operation as `kill()`, and the child
gets no chance to flush.

## Develop

```bash
cd kotlin
./gradlew build
./gradlew test
./gradlew publishToMavenLocal
```

`explicitApi()` and `allWarningsAsErrors` are both on, so every public declaration needs an
explicit visibility and a warning fails the build.

## License

Apache-2.0. See [LICENSE](LICENSE).

---

If Kryon saves you time, you can
[buy me a coffee](https://buymeacoffee.com/piyushmishra00).
