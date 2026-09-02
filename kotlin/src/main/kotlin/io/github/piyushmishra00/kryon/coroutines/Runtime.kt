package io.github.piyushmishra00.kryon.coroutines

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private val WINDOWS: Boolean =
    System.getProperty("os.name", "").lowercase(Locale.ROOT).contains("win")

/**
 * Preserved even when `clearEnv` is set. Windows binaries -- including the ones in `System32` --
 * routinely fail to start without these.
 */
private val WINDOWS_ESSENTIAL = listOf("SystemRoot", "SystemDrive")

private const val CHUNK = 65536

/**
 * Bounded channel capacity for streaming output. This is the backpressure knob: once the collector
 * is this far behind, Kryon stops reading, the OS pipe fills, and the child blocks. Bounded memory
 * beats a fast producer every time.
 */
private const val CHANNEL_CAPACITY = 64

/**
 * An execution context holding default options.
 *
 * A `Runtime` holds configuration, not state. It is safe to share, and creating one is cheap
 * enough that you can also just make a new one:
 *
 * ```kotlin
 * val runtime = Runtime(ExecutionOptions(charset = Charsets.UTF_8, timeout = 30.seconds))
 * val result = runtime.execute("git", listOf("status", "--porcelain"))
 * ```
 *
 * Any default can be overridden per call. `env` merges with the runtime's `env`; every other
 * option is replaced.
 *
 * Cancellation is structural: cancelling the coroutine running [execute] terminates the child
 * process before the `CancellationException` propagates. Kryon never resumes a caller while
 * leaving a process running.
 */
public class Runtime(
    /** The options this runtime applies when a call does not override them. */
    public val defaults: ExecutionOptions = ExecutionOptions(),
) {
    init {
        defaults.validate()
    }

    /**
     * Runs [executable] with [arguments] and returns what happened.
     *
     * Arguments are passed to the operating system as a vector. Nothing in them is interpreted --
     * `execute("echo", listOf("\$HOME && rm -rf /"))` prints that text literally. For shell
     * semantics you must ask for them by name; see [executeShell].
     *
     * A process that could not be started throws. A process that started and then failed is
     * returned, because a non-zero exit is information: `grep` exits `1` to mean "no match". Pass
     * `check = true` to throw on those too.
     *
     * @throws CommandNotFoundException the executable could not be resolved
     * @throws PermissionDeniedException the executable could not be run
     * @throws ProcessStartFailedException the process could not be created
     * @throws InvalidArgumentsException the request was malformed
     */
    public suspend fun execute(
        executable: String,
        arguments: List<String> = emptyList(),
        options: ExecutionOptions? = null,
    ): ExecutionResult = run(executable, arguments, options, shell = false)

    /**
     * Runs [commandLine] through the system shell.
     *
     * **The shell interprets quoting, globbing, variable expansion, pipes and command chaining.
     * Building this string from untrusted input is a command-injection vulnerability.** If you are
     * interpolating a value, you almost certainly want [execute] with an argument list instead.
     *
     * The shell is `/bin/sh -c` on POSIX and `cmd.exe /d /s /c` on Windows.
     *
     * This function exists as a separate name on purpose. A `shell = true` flag sitting among a
     * dozen options is easy to set by accident and easy to miss in review; a differently named
     * function is not.
     */
    public suspend fun executeShell(
        commandLine: String,
        options: ExecutionOptions? = null,
    ): ExecutionResult = run(commandLine, emptyList(), options, shell = true)

    private suspend fun run(
        executable: String,
        arguments: List<String>,
        overrides: ExecutionOptions?,
        shell: Boolean,
    ): ExecutionResult {
        val options = defaults.mergedWith(overrides).also { it.validate() }
        normalise(executable)
        checkCwd(options)

        val mark = TimeSource.Monotonic.markNow()
        val process = withContext(Dispatchers.IO) { start(executable, arguments, options, shell) }

        val reason = AtomicReference<TerminationReason?>(null)
        val out = Sink(options.maxOutputBytes)
        val err = Sink(options.maxOutputBytes)

        suspend fun intervene(why: TerminationReason) {
            if (reason.compareAndSet(null, why)) stop(process, options.killGraceOrDefault)
        }

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val pumps = listOf(
            scope.launch { pump(process.inputStream, out) { intervene(TerminationReason.OUTPUT_LIMIT) } },
            scope.launch { pump(process.errorStream, err) { intervene(TerminationReason.OUTPUT_LIMIT) } },
            scope.launch { feed(process, options) },
        )

        try {
            val exited = awaitExit(process, options.timeout)
            if (!exited) {
                intervene(TerminationReason.TIMEOUT)
                withContext(NonCancellable) { awaitExit(process, null) }
            }
        } catch (cancellation: CancellationException) {
            // Resuming the caller with the child still running is not an option, and the
            // cleanup itself must not be cancelled halfway through.
            withContext(NonCancellable) {
                reason.compareAndSet(null, TerminationReason.CANCELLED)
                stop(process, options.killGraceOrDefault)
                pumps.forEach { it.cancelAndJoin() }
                scope.coroutineContext[Job]?.cancel()
            }
            throw cancellation
        }

        withContext(NonCancellable) {
            // Bounded, because a grandchild can hold the pipes open after the child exits.
            withTimeoutOrNull(maxOf(options.killGraceOrDefault.inWholeMilliseconds, 1000L)) {
                pumps.forEach { it.join() }
            }
            pumps.forEach { it.cancelAndJoin() }
            scope.coroutineContext[Job]?.cancel()
        }

        val outcome = classify(process, reason.get())
        val result = ExecutionResult(
            executable = executable,
            arguments = arguments.toList(),
            exitCode = outcome.exitCode,
            signal = outcome.signal,
            stdoutBytes = out.value(),
            stderrBytes = err.value(),
            charset = options.charset,
            duration = mark.elapsedNow(),
            termination = outcome.termination,
            pid = process.pid(),
            stdoutTruncated = out.truncated,
            stderrTruncated = err.truncated,
        )
        return if (options.checkOrDefault) result.checked() else result
    }

    /**
     * Starts [executable] and returns a [KryonProcess] to interact with.
     *
     * Returns as soon as the process has started. Use [KryonProcess.use] so it cannot outlive the
     * block:
     *
     * ```kotlin
     * runtime.spawn("node", listOf("worker.js")).use { proc ->
     *     proc.write("job-1\n")
     *     proc.closeStdin()
     *     proc.output.collect { chunk -> print(chunk.text()) }
     * }
     * ```
     */
    public suspend fun spawn(
        executable: String,
        arguments: List<String> = emptyList(),
        options: ExecutionOptions? = null,
    ): KryonProcess {
        val merged = defaults.mergedWith(options).also { it.validate() }
        normalise(executable)
        checkCwd(merged)
        val process = withContext(Dispatchers.IO) { start(executable, arguments, merged, shell = false) }
        return KryonProcess(process, executable, arguments.toList(), merged)
    }
}

/**
 * A running process you can talk to.
 *
 * Output arrives through [output] as [OutputChunk]s in the order Kryon observed them. Chunk
 * boundaries mean nothing -- they reflect how the operating system delivered the data, not lines
 * or records.
 *
 * The flow is bounded. If you stop collecting, Kryon stops reading, the pipe fills and the child
 * blocks. That is backpressure working, not a hang.
 *
 * Obtain one from [Runtime.spawn], and prefer [use] so it cannot outlive its scope.
 */
public class KryonProcess internal constructor(
    private val process: Process,
    private val executable: String,
    private val arguments: List<String>,
    private val options: ExecutionOptions,
) : AutoCloseable {

    private val mark = TimeSource.Monotonic.markNow()
    private val reason = AtomicReference<TerminationReason?>(null)
    private val bytesSeen = AtomicLong()
    private val consumed = AtomicBoolean()
    private val closed = AtomicBoolean()
    private var stdinClosed = false

    private val channel =
        Channel<OutputChunk>(capacity = CHANNEL_CAPACITY, onBufferOverflow = BufferOverflow.SUSPEND)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val readers = listOf(
        scope.launch { read(StreamKind.STDOUT, process.inputStream) },
        scope.launch { read(StreamKind.STDERR, process.errorStream) },
    )

    private val deadline: Job? = options.timeout?.let { limit ->
        scope.launch {
            delay(limit)
            if (process.isAlive && reason.compareAndSet(null, TerminationReason.TIMEOUT)) {
                stop(process, options.killGraceOrDefault)
            }
        }
    }

    private val open = AtomicLong(readers.size.toLong())

    /** The operating-system process id. */
    public val pid: Long get() = process.pid()

    /** Whether the process is still alive. */
    public val running: Boolean get() = process.isAlive

    /** The exit status once the process has been reaped, otherwise null. */
    public val exitCode: Int? get() = if (process.isAlive) null else process.exitValue()

    /**
     * Chunks of output until both pipes reach end-of-input.
     *
     * There is one collector. Collecting twice throws, because the second would silently steal
     * chunks from the first.
     */
    public val output: Flow<OutputChunk>
        get() {
            check(consumed.compareAndSet(false, true)) {
                "output of pid $pid is already being consumed; a process has one output stream, " +
                    "and two readers would each get an arbitrary half of it"
            }
            return channel.consumeAsFlow()
        }

    /**
     * Writes text to the child's stdin, encoded with the configured charset (UTF-8 when none is
     * set).
     */
    public suspend fun write(text: String): Unit =
        write(text.toByteArray(options.charset ?: Charsets.UTF_8))

    /**
     * Writes bytes to the child's stdin.
     *
     * Throws if stdin is already closed -- dropping input silently is the failure mode that
     * produces hangs nobody can reproduce.
     */
    public suspend fun write(data: ByteArray) {
        check(!stdinClosed) { "stdin of pid $pid is closed" }
        withContext(Dispatchers.IO) {
            try {
                process.outputStream.write(data)
                process.outputStream.flush()
            } catch (ignored: IOException) {
                // The child stopped reading. Its exit code, not this write, is the story.
            }
        }
    }

    /** Closes stdin, signalling end-of-input to the child. */
    public fun closeStdin() {
        if (stdinClosed) return
        stdinClosed = true
        try {
            process.outputStream.close()
        } catch (ignored: IOException) {
            // Already gone.
        }
    }

    /**
     * Sends a specific signal to the process.
     *
     * The JDK exposes no general signal API, so this supports the two signals [Process] can send:
     * `SIGTERM` (15) and `SIGKILL` (9).
     *
     * @throws UnsupportedPlatformException on Windows, which has no signals to send
     */
    public fun signal(signalNumber: Int) {
        if (WINDOWS) {
            throw UnsupportedPlatformException(
                "Windows has no signals; use terminate(), which kills the process outright " +
                    "without letting it clean up",
            )
        }
        when (signalNumber) {
            15 -> process.destroy()
            9 -> process.destroyForcibly()
            else -> throw InvalidArgumentsException(
                "the JVM can only send SIGTERM (15) and SIGKILL (9); $signalNumber would need a " +
                    "native call Kryon does not make",
            )
        }
    }

    /**
     * Requests a polite stop: `SIGTERM` on POSIX, `TerminateProcess` on Windows.
     *
     * On Windows this is identical to [kill]. There is no graceful stop.
     */
    public fun terminate() {
        process.destroy()
    }

    /** Forces a stop: `SIGKILL` on POSIX, `TerminateProcess` on Windows. */
    public fun kill() {
        process.destroyForcibly()
    }

    /**
     * Waits for exit and returns the outcome.
     *
     * `stdout` and `stderr` on the result are empty: the output was streamed to you through
     * [output] and is deliberately not buffered a second time.
     *
     * @throws ProcessTimeoutException if [timeout] elapses. The process is left running -- this is
     *   a wait, not a stop. Call [terminate] or [close] for that.
     */
    public suspend fun await(timeout: Duration? = null): ExecutionResult {
        if (!awaitExit(process, timeout)) {
            throw ProcessTimeoutException(
                "'$executable' (pid $pid) still running after $timeout",
            )
        }
        deadline?.cancel()
        val outcome = classify(process, reason.get())
        return ExecutionResult(
            executable = executable,
            arguments = arguments,
            exitCode = outcome.exitCode,
            signal = outcome.signal,
            stdoutBytes = ByteArray(0),
            stderrBytes = ByteArray(0),
            charset = options.charset,
            duration = mark.elapsedNow(),
            termination = outcome.termination,
            pid = process.pid(),
        )
    }

    /**
     * Terminates the process if it is still running and releases every resource.
     *
     * Idempotent. Prefer [use] over calling this by hand.
     */
    public suspend fun closeAndJoin() {
        if (!closed.compareAndSet(false, true)) return
        deadline?.cancel()
        closeStdin()

        if (process.isAlive) {
            reason.compareAndSet(null, TerminationReason.CANCELLED)
            stop(process, options.killGraceOrDefault)
        }
        // A reader suspended on a full channel never sees the pipe close, so an unconsumed
        // process would leak two coroutines and two file descriptors. Cancelling releases them.
        readers.forEach { it.cancelAndJoin() }
        channel.close()
        scope.coroutineContext[Job]?.cancel()
    }

    /**
     * Blocking close, for `AutoCloseable` and Java interoperation.
     *
     * Prefer [use], which is the suspending version and does not block a thread.
     */
    override fun close() {
        kotlinx.coroutines.runBlocking { closeAndJoin() }
    }

    /** Runs [block] with this process and closes it afterwards, whatever happens. */
    public suspend fun <R> use(block: suspend (KryonProcess) -> R): R =
        try {
            block(this)
        } finally {
            withContext(NonCancellable) { closeAndJoin() }
        }

    private suspend fun read(tag: StreamKind, source: InputStream) {
        val buffer = ByteArray(CHUNK)
        try {
            while (true) {
                val read = withContext(Dispatchers.IO) { source.read(buffer) }
                if (read == -1) break
                val total = bytesSeen.addAndGet(read.toLong())
                val limit = options.maxOutputBytes
                if (limit != null && total > limit &&
                    reason.compareAndSet(null, TerminationReason.OUTPUT_LIMIT)
                ) {
                    stop(process, options.killGraceOrDefault)
                }
                channel.send(OutputChunk(tag, buffer.copyOf(read)))
            }
        } catch (ignored: IOException) {
            // Pipe closed underneath us during shutdown.
        } catch (cancellation: CancellationException) {
            throw cancellation
        } finally {
            if (open.decrementAndGet() == 0L) channel.close()
        }
    }

    override fun toString(): String =
        "KryonProcess(pid=$pid, $executable, ${if (running) "running" else "exited $exitCode"})"
}

// ---------------------------------------------------------------------- internals

/**
 * Validates the command, or throws [InvalidArgumentsException].
 *
 * Shorter than its siblings on purpose: Kotlin's type system already rules out a non-string
 * argument at compile time, so the only mistake left to catch at runtime is an empty executable.
 */
private fun normalise(executable: String) {
    if (executable.isEmpty()) {
        throw InvalidArgumentsException("executable must not be empty")
    }
}

private fun checkCwd(options: ExecutionOptions) {
    val cwd = options.cwd ?: return
    if (!Files.exists(cwd)) {
        throw ProcessStartFailedException("working directory does not exist: $cwd")
    }
    if (!Files.isDirectory(cwd)) {
        throw ProcessStartFailedException("working directory is not a directory: $cwd")
    }
}

private fun start(
    executable: String,
    arguments: List<String>,
    options: ExecutionOptions,
    shell: Boolean,
): Process {
    val command = if (shell) {
        if (WINDOWS) {
            listOf(System.getenv("COMSPEC") ?: "cmd.exe", "/d", "/s", "/c", executable)
        } else {
            listOf("/bin/sh", "-c", executable)
        }
    } else {
        listOf(executable) + arguments
    }

    val builder = ProcessBuilder(command)
    options.cwd?.let { builder.directory(it.toFile()) }

    val environment = builder.environment()
    if (options.clearEnvOrDefault) {
        val preserved = if (WINDOWS) {
            WINDOWS_ESSENTIAL.mapNotNull { key -> environment[key]?.let { key to it } }.toMap()
        } else {
            emptyMap()
        }
        environment.clear()
        environment.putAll(preserved)
    }
    options.env.forEach { (key, value) ->
        if (value == null) environment.remove(key) else environment[key] = value
    }

    return try {
        builder.start()
    } catch (error: IOException) {
        throw mapStartError(error, executable)
    }
}

private fun mapStartError(error: IOException, executable: String): KryonException {
    val message = error.message.orEmpty()
    val lower = message.lowercase(Locale.ROOT)
    return when {
        lower.contains("no such file") || lower.contains("cannot find") ||
            lower.contains("error=2") || lower.contains("error=3") ->
            CommandNotFoundException("executable not found: '$executable'", error)
        lower.contains("permission denied") || lower.contains("access is denied") ||
            lower.contains("error=13") ->
            PermissionDeniedException("not permitted to execute '$executable': $message", error)
        else -> ProcessStartFailedException("could not start '$executable': $message", error)
    }
}

internal class Outcome(
    val termination: TerminationReason,
    val exitCode: Int?,
    val signal: Int?,
)

/**
 * Maps a raw exit value plus any Kryon intervention to the reported outcome.
 *
 * The JVM reports a signal death as `128 + signum` on POSIX and exposes no signal number of its
 * own, which is why `signal` is derived rather than read. A program that genuinely exits in that
 * range is indistinguishable; the limitation is documented in `docs/guides/platform-support.md`.
 */
internal fun classify(process: Process, reason: TerminationReason?): Outcome {
    val raw = process.exitValue()
    val signaled = !WINDOWS && raw in 129..191
    val natural = if (signaled) TerminationReason.SIGNALED else TerminationReason.EXITED
    return Outcome(
        termination = reason ?: natural,
        exitCode = if (signaled) null else raw,
        signal = if (signaled) raw - 128 else null,
    )
}

/**
 * Waits for exit, returning false if [timeout] elapsed first.
 *
 * `runInterruptible` rather than a plain `withContext`: `Process.waitFor` is a blocking call, and
 * cancelling a coroutine does not by itself unblock one. Without the interrupt, cancelling an
 * `execute` would sit here until the child finished on its own -- which is exactly the "returns
 * control while leaving a process running" failure the whole design is meant to prevent.
 */
internal suspend fun awaitExit(process: Process, timeout: Duration?): Boolean =
    runInterruptible(Dispatchers.IO) {
        if (timeout == null) {
            process.waitFor()
            true
        } else {
            process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        }
    }

/**
 * Terminates politely, then kills. The single termination path in this SDK.
 *
 * On Windows both steps are `TerminateProcess`: there is no graceful stop, and the child gets no
 * chance to flush.
 */
internal suspend fun stop(process: Process, killGrace: Duration) {
    if (!process.isAlive) return
    withContext(Dispatchers.IO + NonCancellable) {
        process.destroy()
        if (process.waitFor(killGrace.inWholeMilliseconds, TimeUnit.MILLISECONDS)) return@withContext
        process.destroyForcibly()
        process.waitFor(killGrace.inWholeMilliseconds, TimeUnit.MILLISECONDS)
    }
}

private suspend fun pump(source: InputStream, sink: Sink, onLimit: suspend () -> Unit) {
    val buffer = ByteArray(CHUNK)
    try {
        while (true) {
            val read = withContext(Dispatchers.IO) { source.read(buffer) }
            if (read == -1) break
            if (sink.add(buffer, read)) onLimit()
        }
    } catch (ignored: IOException) {
        // Pipe closed underneath us during shutdown.
    }
}

private suspend fun feed(process: Process, options: ExecutionOptions) {
    withContext(Dispatchers.IO) {
        try {
            process.outputStream.use { stdin ->
                options.stdin?.let {
                    stdin.write(it)
                    stdin.flush()
                }
            }
        } catch (ignored: IOException) {
            // The child exited before reading its input; its exit code is the story.
        }
    }
}

/** A byte sink that stops growing at a limit and remembers that it did. */
internal class Sink(private val limit: Long?) {
    private val buffer = ByteArrayOutputStream()

    @Volatile
    var truncated: Boolean = false
        private set

    /**
     * Appends [length] bytes. Returns true when the limit has just been exceeded.
     *
     * Data past the limit is dropped rather than counted: the point of a cap is not to hold the
     * bytes.
     */
    @Synchronized
    fun add(data: ByteArray, length: Int): Boolean {
        if (limit == null) {
            buffer.write(data, 0, length)
            return false
        }
        val room = limit - buffer.size()
        if (room > 0) buffer.write(data, 0, minOf(room, length.toLong()).toInt())
        if (length > room) {
            truncated = true
            return true
        }
        return false
    }

    @Synchronized
    fun value(): ByteArray = buffer.toByteArray()
}
