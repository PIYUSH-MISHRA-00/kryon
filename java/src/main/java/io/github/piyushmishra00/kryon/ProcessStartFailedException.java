package io.github.piyushmishra00.kryon;

/**
 * The process could not be created, for a reason other than not being found or not being
 * permitted.
 *
 * <p>A missing working directory, a resource limit, a platform refusal. The underlying
 * exception is preserved as the cause.
 */
public class ProcessStartFailedException extends KryonException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message what went wrong
     */
    public ProcessStartFailedException(String message) {
        super(message);
    }

    /**
     * Creates the exception with an underlying cause.
     *
     * @param message what went wrong
     * @param cause the platform error underneath
     */
    public ProcessStartFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
