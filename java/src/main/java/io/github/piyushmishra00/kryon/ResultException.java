package io.github.piyushmishra00.kryon;

/**
 * Base for errors that describe a process which really ran.
 *
 * <p>Every one of these carries the {@link ExecutionResult} it came from. An error that discards
 * the stderr explaining what went wrong is a worse error than no error at all.
 */
public class ResultException extends KryonException {

    private static final long serialVersionUID = 1L;

    private final transient ExecutionResult result;

    /**
     * Creates the exception.
     *
     * @param message what went wrong
     * @param result the result it came from, or {@code null} when none exists
     */
    public ResultException(String message, ExecutionResult result) {
        super(message);
        this.result = result;
    }

    /**
     * The result this error came from.
     *
     * @return the result, or {@code null} if the error was raised without one
     */
    public ExecutionResult result() {
        return result;
    }
}
