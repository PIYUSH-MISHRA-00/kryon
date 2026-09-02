package io.github.piyushmishra00.kryon;

/**
 * Why the process stopped.
 *
 * <p>The three Kryon-initiated reasons take precedence over the operating system's own account of
 * the death. A process killed because it exceeded its timeout was, at the kernel level,
 * {@link #SIGNALED} -- but {@link #TIMEOUT} is the fact the caller needs in order to react
 * correctly, so that is what is reported.
 */
public enum TerminationReason {

    /** The process exited on its own. The exit code is present. */
    EXITED,

    /** The process was killed by a signal Kryon did not send. POSIX only. */
    SIGNALED,

    /** The timeout elapsed and Kryon terminated the process. */
    TIMEOUT,

    /** The caller cancelled and Kryon terminated the process. */
    CANCELLED,

    /** The output limit was exceeded and Kryon stopped the process. */
    OUTPUT_LIMIT
}
