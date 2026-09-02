package io.github.piyushmishra00.kryon;

/**
 * The operation cannot exist on this platform.
 *
 * <p>Distinct from a transient failure: this means <em>never here</em>, not <em>not right
 * now</em>. Sending an arbitrary signal on Windows is the current example.
 */
public class UnsupportedPlatformException extends KryonException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message what went wrong
     */
    public UnsupportedPlatformException(String message) {
        super(message);
    }

    /**
     * Creates the exception with an underlying cause.
     *
     * @param message what went wrong
     * @param cause the platform error underneath
     */
    public UnsupportedPlatformException(String message, Throwable cause) {
        super(message, cause);
    }
}
