package io.github.piyushmishra00.kryon;

/**
 * Base class for everything Kryon throws.
 *
 * <p>The organising rule, from {@code spec/errors.md}:
 *
 * <blockquote>Failing to start is an error. Failing while running is a result.</blockquote>
 *
 * <p>A command that could not be found never ran, so there is no result to return and
 * {@link CommandNotFoundException} is thrown. A command that ran and exited {@code 1} did run --
 * {@code grep} exits {@code 1} to mean "no match" -- so that is reported in
 * {@link ExecutionResult}, not thrown. Callers who want the strict style set
 * {@code check(true)}, which turns unsuccessful results into the errors below.
 *
 * <p>These are unchecked exceptions. A checked exception on every {@code execute} call would push
 * callers towards catching and ignoring, which is worse than the alternative.
 */
public class KryonException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception with a message.
     *
     * @param message what went wrong
     */
    public KryonException(String message) {
        super(message);
    }

    /**
     * Creates an exception with a message and an underlying cause.
     *
     * @param message what went wrong
     * @param cause the platform error underneath, preserved rather than flattened
     */
    public KryonException(String message, Throwable cause) {
        super(message, cause);
    }
}
