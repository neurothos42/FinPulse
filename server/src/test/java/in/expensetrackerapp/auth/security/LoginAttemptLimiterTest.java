package in.expensetrackerapp.auth.security;

import in.expensetrackerapp.common.exception.TooManyRequestsException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoginAttemptLimiterTest {

    /** A clock the tests can move. */
    static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration d) { now = now.plus(d); }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private final MutableClock clock = new MutableClock();
    private final LoginAttemptLimiter limiter = new LoginAttemptLimiter(clock, 3, Duration.ofMinutes(15));

    private void fail(String key, int times) {
        for (int i = 0; i < times; i++) {
            limiter.assertAllowed(key);
            limiter.recordFailure(key);
        }
    }

    @Test
    void attemptsBelowTheLimitAreAllowed() {
        fail("a@x.com", 3);                                   // three failures are recorded, none was blocked
        assertThatThrownBy(() -> limiter.assertAllowed("a@x.com")).isInstanceOf(TooManyRequestsException.class);
        assertThatCode(() -> limiter.assertAllowed("b@x.com")).doesNotThrowAnyException();
    }

    @Test
    void theLockReportsHowLongToWait() {
        fail("a@x.com", 3);
        clock.advance(Duration.ofMinutes(5));

        assertThatThrownBy(() -> limiter.assertAllowed("a@x.com"))
                .isInstanceOfSatisfying(TooManyRequestsException.class, e -> {
                    assertThat(e.getRetryAfterSeconds()).isEqualTo(600);            // 15 min window, 5 min elapsed
                    assertThat(e.getMessage()).doesNotContain("a@x.com");
                });
    }

    @Test
    void theLockEndsWhenTheWindowEnds() {
        fail("a@x.com", 3);
        clock.advance(Duration.ofMinutes(15));

        assertThatCode(() -> limiter.assertAllowed("a@x.com")).doesNotThrowAnyException();
        limiter.recordFailure("a@x.com");                    // a new window starts with a single failure
        assertThatCode(() -> limiter.assertAllowed("a@x.com")).doesNotThrowAnyException();
    }

    @Test
    void aSuccessfulLoginClearsTheCounter() {
        fail("a@x.com", 2);
        limiter.recordSuccess("a@x.com");
        fail("a@x.com", 2);

        assertThatCode(() -> limiter.assertAllowed("a@x.com")).doesNotThrowAnyException();
    }

    @Test
    void failuresOfOneKeyNeverAffectAnother() {
        fail("a@x.com", 3);
        assertThatCode(() -> limiter.assertAllowed("other@x.com")).doesNotThrowAnyException();
    }

    @Test
    void failuresOutsideTheWindowDoNotAccumulate() {
        fail("a@x.com", 2);
        clock.advance(Duration.ofMinutes(16));
        fail("a@x.com", 2);

        assertThatCode(() -> limiter.assertAllowed("a@x.com")).doesNotThrowAnyException();
    }

    @Test
    void blockedAttemptsDoNotExtendTheLock() {
        fail("a@x.com", 3);
        clock.advance(Duration.ofMinutes(10));
        assertThatThrownBy(() -> limiter.assertAllowed("a@x.com")).isInstanceOf(TooManyRequestsException.class);
        clock.advance(Duration.ofMinutes(5));

        assertThatCode(() -> limiter.assertAllowed("a@x.com")).doesNotThrowAnyException();
    }

    @Test
    void expiredEntriesAreEvictedSoMemoryStaysBounded() {
        for (int i = 0; i < 10_050; i++) {
            limiter.recordFailure("user" + i + "@x.com");
        }
        clock.advance(Duration.ofMinutes(20));
        limiter.recordFailure("trigger@x.com");

        assertThat(limiter.trackedKeys()).isLessThan(100);
    }

    @Test
    void invalidConfigurationIsRejected() {
        assertThatThrownBy(() -> new LoginAttemptLimiter(clock, 0, Duration.ofMinutes(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LoginAttemptLimiter(clock, 3, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }
}
