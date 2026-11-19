package in.expensetrackerapp.auth.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

@Configuration
public class LoginProtectionConfig {

    @Bean
    public LoginAttemptLimiter loginAttemptLimiter(
            @Value("${application.security.login-max-failures:5}") int maxFailures,
            @Value("${application.security.login-lockout-minutes:15}") long lockoutMinutes) {
        return new LoginAttemptLimiter(Clock.systemUTC(), maxFailures, Duration.ofMinutes(lockoutMinutes));
    }
}
