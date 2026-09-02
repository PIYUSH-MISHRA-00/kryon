package io.github.piyushmishra00.kryon;

/**
 * The timeout elapsed and Kryon terminated the process.
 *
 * <p>{@link #result()} holds the output collected before termination; it is not discarded.
 */
public class ProcessTimeoutException extends ResultException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message what went wrong
     * @param result the result it came from
     */
    public ProcessTimeoutException(String message, ExecutionResult result) {
        super(message, result);
    }
}
