package io.github.piyushmishra00.kryon;

/**
 * The process exited with a non-zero status, and {@code check} was set.
 */
public class ProcessFailedException extends ResultException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message what went wrong
     * @param result the result it came from
     */
    public ProcessFailedException(String message, ExecutionResult result) {
        super(message, result);
    }
}
