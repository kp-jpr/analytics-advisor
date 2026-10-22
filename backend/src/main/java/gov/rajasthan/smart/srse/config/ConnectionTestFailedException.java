package gov.rajasthan.smart.srse.config;

/** Thrown when an admin-supplied connection edit fails its test-connect. */
public class ConnectionTestFailedException extends RuntimeException {
    public ConnectionTestFailedException(String plane, String message) {
        this(plane, message, null);
    }

    public ConnectionTestFailedException(String plane, String message, Throwable cause) {
        super("Failed to connect to the " + plane + " plane with the supplied settings: " + message, cause);
    }
}
