package io.github.piyushmishra00.kryon.coroutines

/**
 * Base class for everything Kryon throws.
 *
 * The organising rule, from `spec/errors.md`:
 *
 * > Failing to start is an error. Failing while running is a result.
 *
 * A command that could not be found never ran, so there is no result to return and
 * [CommandNotFoundException] is thrown. A command that ran and exited `1` did run -- `grep` exits
 * `1` to mean "no match" -- so that is reported in [ExecutionResult], not thrown. Callers who want
 * the strict style set `check = true`, which turns unsuccessful results into the errors below.
 */
public open class KryonException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * The request was malformed before any process was created.
 *
 * An empty executable, a negative timeout, a blank argument -- the things that are cheaper to
 * reject than to clean up after.
 */
public class InvalidArgumentsException(
    message: String,
    cause: Throwable? = null,
) : KryonException(message, cause)

/** The executable could not be resolved on `PATH` or at the given path. */
public class CommandNotFoundException(
    message: String,
    cause: Throwable? = null,
) : KryonException(message, cause)

/** The executable exists but could not be executed, or the working directory could not be entered. */
public class PermissionDeniedException(
    message: String,
    cause: Throwable? = null,
) : KryonException(message, cause)

/**
 * The process could not be created, for a reason other than not being found or not being
 * permitted.
 *
 * A missing working directory, a resource limit, a platform refusal. The underlying exception is
 * preserved as the cause.
 */
public class ProcessStartFailedException(
    message: String,
    cause: Throwable? = null,
) : KryonException(message, cause)

/**
 * Base for errors that describe a process which really ran.
 *
 * Every one of these carries the [ExecutionResult] it came from. An error that discards the stderr
 * explaining what went wrong is a worse error than no error at all.
 */
public open class ResultException(
    message: String,
    /** The result this error came from, when one exists. */
    public val result: ExecutionResult? = null,
) : KryonException(message)

/** The process exited with a non-zero status, and `check` was set. */
public class ProcessFailedException(
    message: String,
    result: ExecutionResult? = null,
) : ResultException(message, result)

/**
 * The timeout elapsed and Kryon terminated the process.
 *
 * [result] holds the output collected before termination; it is not discarded.
 */
public class ProcessTimeoutException(
    message: String,
    result: ExecutionResult? = null,
) : ResultException(message, result)

/** The caller cancelled the operation and Kryon terminated the process. */
public class ProcessCancelledException(
    message: String,
    result: ExecutionResult? = null,
) : ResultException(message, result)

/**
 * An output limit was exceeded and Kryon stopped the process.
 *
 * This is a memory-management mechanism, not a security boundary. See
 * `docs/security/threat-model.md`.
 */
public class ResourceLimitExceededException(
    message: String,
    result: ExecutionResult? = null,
) : ResultException(message, result)

/**
 * The operation cannot exist on this platform.
 *
 * Distinct from a transient failure: this means *never here*, not *not right now*. Sending an
 * arbitrary signal on Windows is the current example.
 */
public class UnsupportedPlatformException(
    message: String,
    cause: Throwable? = null,
) : KryonException(message, cause)
