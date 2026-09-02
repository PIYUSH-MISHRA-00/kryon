package io.github.piyushmishra00.kryon;

/**
 * The caller cancelled the operation and Kryon terminated the process.
 */
public class ProcessCancelledException extends ResultException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message what went wrong
     * @param result the result it came from
     */
    public ProcessCancelledException(String message, ExecutionResult result) {
        super(message, result);
    }
}
