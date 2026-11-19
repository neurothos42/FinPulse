package in.expensetrackerapp.auth.security;

import in.expensetrackerapp.common.exception.TooManyRequestsException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Limits failed login attempts per key (the normalised e-mail address) with a fixed window: after
 * {@code maxFailures} failures inside the window the key is locked until the window ends. Failures are
 * counted for unknown e-mails too, so the lock cannot be used to find out which accounts exist.
 *
 * In-memory: it protects a single application instance. Behind several instances, or against attacks that
 * rotate e-mail addresses, add rate limiting at the proxy / hosting layer as well (see docs/SECURITY.md).
 * Trade-off: someone who knows an address can lock that account for one window; the window is short.
 */
public class LoginAttemptLimiter {

    private record Entry(int failures, Instant windowStart) {
    }

    private static final int CLEANUP_THRESHOLD = 10_000;

    private final ConcurrentHashMap<String, Entry> attempts = new ConcurrentHashMap<>();
    private final Clock clock;
    private final int maxFailures;
    private final Duration window;

    public LoginAttemptLimiter(Clock clock, int maxFailures, Duration window) {
        if (maxFailures < 1) {
            throw new IllegalArgumentException("maxFailures must be at least 1");
        }
        if (window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("window must be positive");
        }
        this.clock = clock;
        this.maxFailures = maxFailures;
        this.window = window;
    }

    /** Throws TooManyRequestsException while the key is locked. Call BEFORE checking credentials. */
    public void assertAllowed(String key) {
        Entry entry = attempts.get(key);
        if (entry == null) {
            return;
        }
        Instant now = clock.instant();
        Instant windowEnd = entry.windowStart().plus(window);
        if (!now.isBefore(windowEnd)) {
            attempts.remove(key, entry);
            return;
        }
        if (entry.failures() >= maxFailures) {
            long retryAfter = Math.max(1, Duration.between(now, windowEnd).toSeconds());
            throw new TooManyRequestsException("Too many failed login attempts. Try again later.", retryAfter);
        }
    }

    public void recordFailure(String key) {
        Instant now = clock.instant();
        attempts.compute(key, (k, entry) -> {
            if (entry == null || !now.isBefore(entry.windowStart().plus(window))) {
                return new Entry(1, now);
            }
            return new Entry(entry.failures() + 1, entry.windowStart());
        });
        if (attempts.size() > CLEANUP_THRESHOLD) {
            attempts.entrySet().removeIf(e -> !now.isBefore(e.getValue().windowStart().plus(window)));
        }
    }

    public void recordSuccess(String key) {
        attempts.remove(key);
    }

    /** Visible for tests. */
    int trackedKeys() {
        return attempts.size();
    }
}
