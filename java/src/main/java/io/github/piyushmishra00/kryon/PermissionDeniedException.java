package io.github.piyushmishra00.kryon;

/**
 * The executable exists but could not be executed, or the working directory could not be
 * entered.
 */
public class PermissionDeniedException extends KryonException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message what went wrong
     */
    public PermissionDeniedException(String message) {
        super(message);
    }

    /**
     * Creates the exception with an underlying cause.
     *
     * @param message what went wrong
     * @param cause the platform error underneath
     */
    public PermissionDeniedException(String message, Throwable cause) {
        super(message, cause);
    }
}
