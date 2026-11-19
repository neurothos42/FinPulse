package in.expensetrackerapp.auth.service;

import in.expensetrackerapp.auth.dto.AuthResponse;
import in.expensetrackerapp.auth.dto.LoginRequest;
import in.expensetrackerapp.auth.dto.RegisterRequest;
import in.expensetrackerapp.auth.security.JwtService;
import in.expensetrackerapp.auth.security.LoginAttemptLimiter;
import in.expensetrackerapp.common.exception.BadRequestException;
import in.expensetrackerapp.common.exception.DuplicateEmailException;
import in.expensetrackerapp.common.exception.InvalidCredentialsException;
import in.expensetrackerapp.user.dto.UserResponse;
import in.expensetrackerapp.user.model.User;
import in.expensetrackerapp.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

@Service
public class AuthServiceImpl implements AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthServiceImpl.class);

    /** BCrypt only uses the first 72 bytes of a password, so longer ones are rejected instead of silently truncated. */
    static final int MAX_PASSWORD_BYTES = 72;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final LoginAttemptLimiter loginAttemptLimiter;
    /** A real hash verified for unknown e-mails, so "no such user" costs the same time as "wrong password". */
    private final String dummyHash;

    @Autowired
    public AuthServiceImpl(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            LoginAttemptLimiter loginAttemptLimiter) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.loginAttemptLimiter = loginAttemptLimiter;
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    public AuthServiceImpl(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService) {
        this(userRepository, passwordEncoder, jwtService,
                new LoginAttemptLimiter(Clock.systemUTC(), 5, Duration.ofMinutes(15)));
    }

    @Override
    public UserResponse register(RegisterRequest request) {
        String normalizedEmail = request.getEmail().toLowerCase().trim();
        if (exceedsBcryptLimit(request.getPassword())) {
            throw new BadRequestException("Password must be at most " + MAX_PASSWORD_BYTES
                    + " bytes long (fewer characters if it contains non-ASCII characters)");
        }

        // Application-level check
        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new DuplicateEmailException("An account with email " + normalizedEmail + " already exists");
        }

        String hashedPassword = passwordEncoder.encode(request.getPassword());
        User user = new User(request.getName().trim(), normalizedEmail, hashedPassword);

        // Concurrency-safe persistence check against database unique index
        User savedUser;
        try {
            savedUser = userRepository.save(user);
        } catch (DuplicateKeyException e) {
            log.warn("Concurrent registration conflict detected for duplicate email: {}", maskEmail(normalizedEmail));
            throw new DuplicateEmailException("An account with email " + normalizedEmail + " already exists");
        }

        log.info("User registered successfully: {}", maskEmail(normalizedEmail));
        return UserResponse.fromUser(savedUser);
    }

    @Override
    public AuthResponse login(LoginRequest request) {
        String normalizedEmail = request.getEmail().toLowerCase().trim();
        String password = request.getPassword();

        loginAttemptLimiter.assertAllowed(normalizedEmail);          // 429 while locked, before any credential work

        Optional<User> user = userRepository.findByEmail(normalizedEmail);
        boolean valid = false;
        if (!exceedsBcryptLimit(password)) {
            if (user.isPresent()) {
                valid = passwordEncoder.matches(password, user.get().getPasswordHash());
            } else {
                passwordEncoder.matches(password, dummyHash);        // same cost as a real check; result ignored
            }
        }
        if (!valid) {
            loginAttemptLimiter.recordFailure(normalizedEmail);      // unknown e-mails count too
            throw new InvalidCredentialsException("Invalid email or password");
        }
        loginAttemptLimiter.recordSuccess(normalizedEmail);

        String token = jwtService.generateToken(user.get());

        log.info("User logged in: {}", maskEmail(normalizedEmail));
        return new AuthResponse(
                token,
                "Bearer",
                jwtService.getExpirationInSeconds(),
                UserResponse.fromUser(user.get())
        );
    }

    private static boolean exceedsBcryptLimit(String password) {
        return password != null && password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES;
    }

    /** "asha@example.com" -> "a***@example.com": enough to correlate log lines, not enough to harvest addresses. */
    static String maskEmail(String email) {
        if (email == null) {
            return "";
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }
}
