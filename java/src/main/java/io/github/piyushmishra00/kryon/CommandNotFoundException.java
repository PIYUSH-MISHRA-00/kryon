package io.github.piyushmishra00.kryon;

/**
 * The executable could not be resolved on {@code PATH} or at the given path.
 */
public class CommandNotFoundException extends KryonException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message what went wrong
     */
    public CommandNotFoundException(String message) {
        super(message);
    }

    /**
     * Creates the exception with an underlying cause.
     *
     * @param message what went wrong
     * @param cause the platform error underneath
     */
    public CommandNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
