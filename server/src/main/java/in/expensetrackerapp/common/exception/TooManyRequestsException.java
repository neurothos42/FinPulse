package in.expensetrackerapp.common.exception;

/** Thrown when a client has used up an attempt budget (e.g. failed logins). Maps to HTTP 429 with Retry-After. */
public class TooManyRequestsException extends RuntimeException {

    private final long retryAfterSeconds;

    public TooManyRequestsException(String message, long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
