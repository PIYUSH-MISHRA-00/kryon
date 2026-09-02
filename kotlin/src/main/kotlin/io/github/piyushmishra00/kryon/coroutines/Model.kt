package io.github.piyushmishra00.kryon.coroutines

import java.nio.charset.Charset
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Which pipe a chunk of output arrived on.
 *
 * Named [StreamKind] rather than `Stream`: on the JVM that name collides with
 * `java.util.stream.Stream`, which Kotlin code reaches for often enough that shadowing it would be
 * a nuisance for every caller. The specification fixes semantics, not spelling.
 */
public enum class StreamKind {
    /** Standard output. */
    STDOUT,

    /** Standard error. */
    STDERR,
}

/**
 * Why the process stopped.
 *
 * The three Kryon-initiated reasons take precedence over the operating system's own account of the
 * death. A process killed because it exceeded its timeout was, at the kernel level, [SIGNALED] --
 * but [TIMEOUT] is the fact the caller needs in order to react correctly, so that is what is
 * reported.
 */
public enum class TerminationReason {
    /** The process exited on its own. The exit code is present. */
    EXITED,

    /** The process was killed by a signal Kryon did not send. POSIX only. */
    SIGNALED,

    /** The timeout elapsed and Kryon terminated the process. */
    TIMEOUT,

    /** The caller cancelled and Kryon terminated the process. */
    CANCELLED,

    /** The output limit was exceeded and Kryon stopped the process. */
    OUTPUT_LIMIT,
}

/**
 * One chunk of output, tagged with the pipe it arrived on.
 *
 * Chunk boundaries mean nothing. They reflect how the operating system delivered the data, not
 * lines or records.
 */
public class OutputChunk(
    /** Which pipe the bytes arrived on. */
    public val stream: StreamKind,
    private val bytes: ByteArray,
) {
    /** The bytes of this chunk, copied so a retained chunk cannot be mutated underneath you. */
    public val data: ByteArray
        get() = bytes.copyOf()

    /** The bytes decoded as UTF-8, replacing anything malformed. */
    public fun text(charset: Charset = Charsets.UTF_8): String = String(bytes, charset)

    /** How many bytes this chunk carries. */
    public val size: Int get() = bytes.size

    override fun toString(): String = "OutputChunk($stream, ${bytes.size} bytes)"
}

/**
 * Options for a single execution.
 *
 * A [Runtime] carries defaults and each call may override them with [mergedWith]; [env] merges,
 * everything else is replaced. Every field is nullable so that "not specified" stays
 * distinguishable from "specified as false" -- without that, a per-call override could turn a flag
 * on but never off.
 */
public data class ExecutionOptions(
    /**
     * Working directory. Inherited when null. A path that is not a directory is an error, never a
     * silent fallback to the current directory.
     */
    public val cwd: Path? = null,
    /**
     * Variables merged *over* the inherited environment. A null value removes the variable. To
     * control the environment strictly, combine with [clearEnv].
     */
    public val env: Map<String, String?> = emptyMap(),
    /**
     * Start from an empty environment instead of inheriting one. With [env], this is an allowlist.
     * On Windows, `SystemRoot` and `SystemDrive` are still preserved, because many binaries fail
     * to start without them.
     */
    public val clearEnv: Boolean? = null,
    /** Data written to the child's stdin, after which stdin is closed. */
    public val stdin: ByteArray? = null,
    /**
     * Wall-clock limit. On expiry the process is terminated politely, then killed after
     * [killGrace]. Output collected so far is kept.
     */
    public val timeout: Duration? = null,
    /**
     * Per-stream cap in bytes, counted before decoding. Exceeding it stops the process and sets
     * the matching truncation flag.
     */
    public val maxOutputBytes: Long? = null,
    /**
     * When set, output is decoded with this charset; when null, output stays as bytes.
     *
     * Decoding is lossy by design: an output cap can cut a multi-byte character in half, and
     * throwing on that would turn a truncation into a crash.
     */
    public val charset: Charset? = null,
    /** Throw instead of returning when the result is not successful. */
    public val check: Boolean? = null,
    /** Time between the polite stop and the forced kill. */
    public val killGrace: Duration? = null,
) {
    /** Whether to start from an empty environment. */
    public val clearEnvOrDefault: Boolean get() = clearEnv ?: false

    /** Whether to throw on an unsuccessful result. */
    public val checkOrDefault: Boolean get() = check ?: false

    /** The grace period between the polite stop and the forced kill. */
    public val killGraceOrDefault: Duration get() = killGrace ?: 5.seconds

    /** Returns a copy with [overrides] applied. [env] merges rather than replaces. */
    public fun mergedWith(overrides: ExecutionOptions?): ExecutionOptions {
        if (overrides == null) return this
        return ExecutionOptions(
            cwd = overrides.cwd ?: cwd,
            env = env + overrides.env,
            clearEnv = overrides.clearEnv ?: clearEnv,
            stdin = overrides.stdin ?: stdin,
            timeout = overrides.timeout ?: timeout,
            maxOutputBytes = overrides.maxOutputBytes ?: maxOutputBytes,
            charset = overrides.charset ?: charset,
            check = overrides.check ?: check,
            killGrace = overrides.killGrace ?: killGrace,
        )
    }

    /** Rejects a malformed request before anything is spawned. */
    public fun validate() {
        if (timeout != null && timeout <= Duration.ZERO) {
            throw InvalidArgumentsException("timeout must be positive, got $timeout")
        }
        if (maxOutputBytes != null && maxOutputBytes <= 0) {
            throw InvalidArgumentsException("maxOutputBytes must be positive, got $maxOutputBytes")
        }
        if (killGrace != null && killGrace < Duration.ZERO) {
            throw InvalidArgumentsException("killGrace must not be negative, got $killGrace")
        }
    }

    // `stdin` is a ByteArray, so the generated equals/hashCode would compare by identity.
    // Overriding keeps a data class honest rather than subtly wrong.
    override fun equals(other: Any?): Boolean =
        this === other ||
            (
                other is ExecutionOptions &&
                    cwd == other.cwd &&
                    env == other.env &&
                    clearEnv == other.clearEnv &&
                    stdin.contentEquals(other.stdin) &&
                    timeout == other.timeout &&
                    maxOutputBytes == other.maxOutputBytes &&
                    charset == other.charset &&
                    check == other.check &&
                    killGrace == other.killGrace
                )

    override fun hashCode(): Int {
        var result = cwd?.hashCode() ?: 0
        result = 31 * result + env.hashCode()
        result = 31 * result + (clearEnv?.hashCode() ?: 0)
        result = 31 * result + (stdin?.contentHashCode() ?: 0)
        result = 31 * result + (timeout?.hashCode() ?: 0)
        result = 31 * result + (maxOutputBytes?.hashCode() ?: 0)
        result = 31 * result + (charset?.hashCode() ?: 0)
        result = 31 * result + (check?.hashCode() ?: 0)
        result = 31 * result + (killGrace?.hashCode() ?: 0)
        return result
    }
}

/**
 * What happened when a process ran.
 *
 * A result exists only for a process that actually started. Failures to start throw; see
 * [KryonException].
 */
public class ExecutionResult internal constructor(
    /** The executable as requested. */
    public val executable: String,
    /** The argument vector as requested. */
    public val arguments: List<String>,
    /** The exit status, or null if the process did not exit normally. */
    public val exitCode: Int?,
    /** The terminating signal where the platform reports one. Always null on Windows. */
    public val signal: Int?,
    private val stdoutBytes: ByteArray,
    private val stderrBytes: ByteArray,
    private val charset: Charset?,
    /** Wall-clock time from spawn to reap. */
    public val duration: Duration,
    /** Why the process stopped. */
    public val termination: TerminationReason,
    /** The operating-system process id. */
    public val pid: Long?,
    /** Whether the output cap discarded standard output. */
    public val stdoutTruncated: Boolean = false,
    /** Whether the output cap discarded standard error. */
    public val stderrTruncated: Boolean = false,
) {
    /** Captured standard output as bytes. */
    public val stdoutRaw: ByteArray get() = stdoutBytes.copyOf()

    /** Captured standard error as bytes. */
    public val stderrRaw: ByteArray get() = stderrBytes.copyOf()

    /** Captured standard output, decoded with the configured charset (UTF-8 when none was set). */
    public val stdout: String get() = String(stdoutBytes, charset ?: Charsets.UTF_8)

    /** Captured standard error, decoded with the configured charset. */
    public val stderr: String get() = String(stderrBytes, charset ?: Charsets.UTF_8)

    /** True only for a process that exited on its own with status `0`. */
    public val ok: Boolean
        get() = termination == TerminationReason.EXITED && exitCode == 0

    /**
     * Returns this result if successful, otherwise throws the matching error.
     *
     * This is what `check = true` calls. Useful on its own when you want to inspect a result first
     * and only then insist it succeeded.
     */
    public fun checked(): ExecutionResult {
        if (ok) return this
        val detail = stderrExcerpt()
        val name = "'$executable'"
        throw when (termination) {
            TerminationReason.TIMEOUT -> ProcessTimeoutException("$name timed out$detail", this)
            TerminationReason.CANCELLED ->
                ProcessCancelledException("$name was cancelled$detail", this)
            TerminationReason.OUTPUT_LIMIT ->
                ResourceLimitExceededException("$name exceeded its output limit$detail", this)
            TerminationReason.SIGNALED ->
                ProcessFailedException("$name was killed by signal $signal$detail", this)
            TerminationReason.EXITED ->
                ProcessFailedException("$name exited with code $exitCode$detail", this)
        }
    }

    /**
     * A short stderr excerpt for the error message.
     *
     * Deliberately capped and deliberately stderr-only: environments and stdin routinely hold
     * credentials, and an error message is the most-pasted string a library produces.
     */
    private fun stderrExcerpt(): String {
        val text = String(stderrBytes, Charsets.UTF_8).trim()
        if (text.isEmpty()) return ""
        return "\nstderr: " + if (text.length > 500) text.take(500) + "..." else text
    }

    override fun toString(): String =
        "ExecutionResult(executable=$executable, exitCode=$exitCode, " +
            "termination=$termination, duration=$duration, " +
            "stdout=${stdoutBytes.size} bytes, stderr=${stderrBytes.size} bytes)"
}
