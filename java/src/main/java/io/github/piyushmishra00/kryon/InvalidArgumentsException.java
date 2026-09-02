package io.github.piyushmishra00.kryon;

/**
 * The request was malformed before any process was created.
 *
 * <p>An empty executable, a negative timeout, a null argument -- the things that are cheaper
 * to reject than to clean up after.
 */
public class InvalidArgumentsException extends KryonException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message what went wrong
     */
    public InvalidArgumentsException(String message) {
        super(message);
    }

    /**
     * Creates the exception with an underlying cause.
     *
     * @param message what went wrong
     * @param cause the platform error underneath
     */
    public InvalidArgumentsException(String message, Throwable cause) {
        super(message, cause);
    }
}
