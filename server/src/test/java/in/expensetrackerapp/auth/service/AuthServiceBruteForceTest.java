package in.expensetrackerapp.auth.service;

import in.expensetrackerapp.auth.dto.LoginRequest;
import in.expensetrackerapp.auth.dto.RegisterRequest;
import in.expensetrackerapp.auth.security.JwtService;
import in.expensetrackerapp.auth.security.LoginAttemptLimiter;
import in.expensetrackerapp.common.exception.BadRequestException;
import in.expensetrackerapp.common.exception.InvalidCredentialsException;
import in.expensetrackerapp.common.exception.TooManyRequestsException;
import in.expensetrackerapp.user.model.User;
import in.expensetrackerapp.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Login hardening: lock-out, equal treatment of unknown e-mails, the BCrypt 72-byte limit. Real BCrypt throughout. */
@ExtendWith(MockitoExtension.class)
class AuthServiceBruteForceTest {

    static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration d) { now = now.plus(d); }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Mock
    private UserRepository userRepository;

    @Mock
    private JwtService jwtService;

    private final MutableClock clock = new MutableClock();
    private BCryptPasswordEncoder encoder;
    private AuthServiceImpl service;
    private User user;

    @BeforeEach
    void setUp() {
        encoder = spy(new BCryptPasswordEncoder());
        service = new AuthServiceImpl(userRepository, encoder, jwtService,
                new LoginAttemptLimiter(clock, 3, Duration.ofMinutes(15)));
        user = new User("Asha", "user@example.com", encoder.encode("Password123"));
    }

    private void wrongLogin(String email) {
        assertThatThrownBy(() -> service.login(new LoginRequest(email, "WrongPassword9")))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    // ------------------------------------------------------------------ lock-out
    @Test
    void afterTooManyFailuresEvenTheCorrectPasswordIsRefusedUntilTheWindowEnds() {
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        for (int i = 0; i < 3; i++) {
            wrongLogin("user@example.com");
        }

        assertThatThrownBy(() -> service.login(new LoginRequest("user@example.com", "Password123")))
                .isInstanceOf(TooManyRequestsException.class);
        verify(userRepository, times(3)).findByEmail("user@example.com");        // the locked attempt did no lookup at all

        clock.advance(Duration.ofMinutes(16));
        when(jwtService.generateToken(user)).thenReturn("token");
        assertThat(service.login(new LoginRequest("user@example.com", "Password123")).getAccessToken()).isEqualTo("token");
    }

    @Test
    void aSuccessfulLoginResetsTheFailureCount() {
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(jwtService.generateToken(user)).thenReturn("token");

        wrongLogin("user@example.com");
        wrongLogin("user@example.com");
        service.login(new LoginRequest("user@example.com", "Password123"));
        wrongLogin("user@example.com");
        wrongLogin("user@example.com");

        assertThatCode(() -> service.login(new LoginRequest("user@example.com", "Password123"))).doesNotThrowAnyException();
    }

    @Test
    void unknownEmailsAreCountedAndLockedExactlyLikeRealOnes() {
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());
        for (int i = 0; i < 3; i++) {
            wrongLogin("nobody@example.com");
        }

        assertThatThrownBy(() -> service.login(new LoginRequest("nobody@example.com", "Password123")))
                .isInstanceOf(TooManyRequestsException.class);
    }

    @Test
    void emailCaseAndWhitespaceDoNotCreateAFreshAttemptBudget() {
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        wrongLogin("user@example.com");
        wrongLogin("USER@example.com");
        wrongLogin("  User@Example.com ");

        assertThatThrownBy(() -> service.login(new LoginRequest("user@EXAMPLE.com", "Password123")))
                .isInstanceOf(TooManyRequestsException.class);
    }

    // ------------------------------------------------------------------ timing / enumeration
    @Test
    void anUnknownEmailStillPaysForABcryptVerification() {
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        wrongLogin("nobody@example.com");

        // one verification against the dummy hash, so "no such user" is not measurably faster than "wrong password"
        verify(encoder, times(1)).matches(eq("WrongPassword9"), anyString());
    }

    @Test
    void unknownAndWrongPasswordResponsesAreIdentical() {
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        Throwable wrong = org.assertj.core.api.Assertions.catchThrowable(
                () -> service.login(new LoginRequest("user@example.com", "WrongPassword9")));
        Throwable unknown = org.assertj.core.api.Assertions.catchThrowable(
                () -> service.login(new LoginRequest("nobody@example.com", "WrongPassword9")));

        assertThat(wrong).isInstanceOf(InvalidCredentialsException.class);
        assertThat(unknown).isInstanceOf(InvalidCredentialsException.class).hasMessage(wrong.getMessage());
    }

    // ------------------------------------------------------------------ BCrypt 72-byte limit
    @Test
    void passwordsOfExactly72BytesAreAcceptedAndLongerOnesAreRejectedAtRegistration() {
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThatCode(() -> service.register(new RegisterRequest("A", "a@example.com", "x".repeat(72)))).doesNotThrowAnyException();
        assertThatThrownBy(() -> service.register(new RegisterRequest("A", "b@example.com", "x".repeat(73))))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("72");
    }

    @Test
    void theLimitIsInBytesNotCharacters() {
        String threeBytesEach = "\u20AC".repeat(25);                        // 25 characters = 75 bytes
        assertThatThrownBy(() -> service.register(new RegisterRequest("A", "c@example.com", threeBytesEach)))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void anOverlongLoginPasswordIsJustInvalidCredentialsAndNeverReachesTheEncoder() {
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.login(new LoginRequest("user@example.com", "x".repeat(200))))
                .isInstanceOf(InvalidCredentialsException.class);
        verify(encoder, never()).matches(eq("x".repeat(200)), anyString());
    }

    // ------------------------------------------------------------------ logging
    @Test
    void emailsAreMaskedForTheLogs() {
        assertThat(AuthServiceImpl.maskEmail("asha@example.com")).isEqualTo("a***@example.com");
        assertThat(AuthServiceImpl.maskEmail("not-an-email")).isEqualTo("***");
        assertThat(AuthServiceImpl.maskEmail("@example.com")).isEqualTo("***");
        assertThat(AuthServiceImpl.maskEmail(null)).isEmpty();
        assertThat(AuthServiceImpl.maskEmail("asha@example.com")).doesNotContain("asha");
    }
}
