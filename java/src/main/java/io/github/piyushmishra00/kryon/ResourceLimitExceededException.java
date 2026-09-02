package io.github.piyushmishra00.kryon;

/**
 * An output limit was exceeded and Kryon stopped the process.
 *
 * <p>This is a memory-management mechanism, not a security boundary. See
 * {@code docs/security/threat-model.md}.
 */
public class ResourceLimitExceededException extends ResultException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message what went wrong
     * @param result the result it came from
     */
    public ResourceLimitExceededException(String message, ExecutionResult result) {
        super(message, result);
    }
}
