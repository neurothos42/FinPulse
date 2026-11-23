package in.expensetrackerapp.common.exception;

/** An external dependency (e.g. Gemini) is not configured or failed. Mapped to HTTP 503. */
public class ServiceUnavailableException extends RuntimeException {
    public ServiceUnavailableException(String message) {
        super(message);
    }
}
