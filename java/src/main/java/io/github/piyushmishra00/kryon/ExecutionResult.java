package io.github.piyushmishra00.kryon;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * What happened when a process ran.
 *
 * <p>A result exists only for a process that actually started. Failures to start throw; see
 * {@link KryonException}.
 */
public final class ExecutionResult {

    private final String executable;
    private final List<String> arguments;
    private final Integer exitCode;
    private final Integer signal;
    private final byte[] stdout;
    private final byte[] stderr;
    private final Charset charset;
    private final Duration duration;
    private final TerminationReason termination;
    private final Long pid;
    private final boolean stdoutTruncated;
    private final boolean stderrTruncated;

    ExecutionResult(
            String executable,
            List<String> arguments,
            Integer exitCode,
            Integer signal,
            byte[] stdout,
            byte[] stderr,
            Charset charset,
            Duration duration,
            TerminationReason termination,
            Long pid,
            boolean stdoutTruncated,
            boolean stderrTruncated) {
        this.executable = executable;
        this.arguments = Collections.unmodifiableList(List.copyOf(arguments));
        this.exitCode = exitCode;
        this.signal = signal;
        this.stdout = stdout;
        this.stderr = stderr;
        this.charset = charset;
        this.duration = duration;
        this.termination = termination;
        this.pid = pid;
        this.stdoutTruncated = stdoutTruncated;
        this.stderrTruncated = stderrTruncated;
    }

    /**
     * The executable as requested.
     *
     * @return the executable
     */
    public String executable() {
        return executable;
    }

    /**
     * The argument vector as requested.
     *
     * @return an unmodifiable list
     */
    public List<String> arguments() {
        return arguments;
    }

    /**
     * The exit status, absent if the process did not exit normally.
     *
     * @return the exit code, if any
     */
    public OptionalInt exitCode() {
        return exitCode == null ? OptionalInt.empty() : OptionalInt.of(exitCode);
    }

    /**
     * The terminating signal where the platform reports one. Always absent on Windows.
     *
     * @return the signal number, if any
     */
    public OptionalInt signal() {
        return signal == null ? OptionalInt.empty() : OptionalInt.of(signal);
    }

    /**
     * Captured standard output as bytes.
     *
     * @return a copy of the bytes
     */
    public byte[] stdoutBytes() {
        return stdout.clone();
    }

    /**
     * Captured standard error as bytes.
     *
     * @return a copy of the bytes
     */
    public byte[] stderrBytes() {
        return stderr.clone();
    }

    /**
     * Captured standard output as text, decoded with the configured charset (UTF-8 when none was
     * set).
     *
     * @return the decoded output
     */
    public String stdout() {
        return new String(stdout, charset == null ? StandardCharsets.UTF_8 : charset);
    }

    /**
     * Captured standard error as text.
     *
     * @return the decoded error output
     */
    public String stderr() {
        return new String(stderr, charset == null ? StandardCharsets.UTF_8 : charset);
    }

    /**
     * Wall-clock time from spawn to reap.
     *
     * @return the duration
     */
    public Duration duration() {
        return duration;
    }

    /**
     * Why the process stopped.
     *
     * @return the termination reason
     */
    public TerminationReason termination() {
        return termination;
    }

    /**
     * The operating-system process id.
     *
     * @return the pid, if known
     */
    public OptionalLong pid() {
        return pid == null ? OptionalLong.empty() : OptionalLong.of(pid);
    }

    /**
     * Whether the output cap discarded standard output.
     *
     * @return true if truncated
     */
    public boolean stdoutTruncated() {
        return stdoutTruncated;
    }

    /**
     * Whether the output cap discarded standard error.
     *
     * @return true if truncated
     */
    public boolean stderrTruncated() {
        return stderrTruncated;
    }

    /**
     * Whether the process exited on its own with status {@code 0}.
     *
     * @return true only for a clean, successful exit
     */
    public boolean ok() {
        return termination == TerminationReason.EXITED && exitCode != null && exitCode == 0;
    }

    /**
     * Returns this result if successful, otherwise throws the matching error.
     *
     * <p>This is what {@code check(true)} calls. Useful on its own when you want to inspect a
     * result first and only then insist it succeeded.
     *
     * @return this result
     */
    public ExecutionResult checked() {
        if (ok()) {
            return this;
        }
        String detail = stderrExcerpt();
        String name = "'" + executable + "'";
        throw switch (termination) {
            case TIMEOUT -> new ProcessTimeoutException(name + " timed out" + detail, this);
            case CANCELLED -> new ProcessCancelledException(name + " was cancelled" + detail, this);
            case OUTPUT_LIMIT ->
                    new ResourceLimitExceededException(
                            name + " exceeded its output limit" + detail, this);
            case SIGNALED ->
                    new ProcessFailedException(
                            name + " was killed by signal " + signal + detail, this);
            case EXITED ->
                    new ProcessFailedException(
                            name + " exited with code " + exitCode + detail, this);
        };
    }

    /**
     * A short stderr excerpt for the error message.
     *
     * <p>Deliberately capped and deliberately stderr-only: environments and stdin routinely hold
     * credentials, and an error message is the most-pasted string a library produces.
     */
    private String stderrExcerpt() {
        String text = new String(stderr, StandardCharsets.UTF_8).trim();
        if (text.isEmpty()) {
            return "";
        }
        return "\nstderr: " + (text.length() > 500 ? text.substring(0, 500) + "..." : text);
    }

    /**
     * A concise description, short enough to read in a debugger.
     *
     * @return the description
     */
    @Override
    public String toString() {
        return "ExecutionResult[executable="
                + executable
                + ", exitCode="
                + Optional.ofNullable(exitCode).map(String::valueOf).orElse("none")
                + ", termination="
                + termination
                + ", duration="
                + duration.toMillis()
                + "ms, stdout="
                + stdout.length
                + " bytes, stderr="
                + stderr.length
                + " bytes]";
    }
}
