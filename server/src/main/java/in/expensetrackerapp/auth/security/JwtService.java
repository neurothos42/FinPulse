package in.expensetrackerapp.auth.security;

import in.expensetrackerapp.config.JwtProperties;
import in.expensetrackerapp.user.model.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.function.Function;

@Service
public class JwtService {

    private final JwtProperties jwtProperties;
    private final SecretKey signingKey;

    public JwtService(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
        // Fail-fast validation at startup: requires JWT_SECRET and validates minimum
        // 256 bits
        jwtProperties.validate();
        this.signingKey = initSigningKey(jwtProperties.getSecret());
    }

    /**
     * Generates a JWT with minimal, stable identity claims:
     * - Subject: user's stable database ID (user.getId())
     * - Issuer: configured issuer
     * - Issued-At: current timestamp
     * - Expiration: current timestamp + configured expiration duration
     */
    public String generateToken(User user) {
        return generateToken(user.getId());
    }

    public String generateToken(String userId) {
        Instant now = Instant.now();
        Instant expiry = now.plusMillis(jwtProperties.getExpirationMs());

        var builder = Jwts.builder()
                .subject(userId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(signingKey);

        if (jwtProperties.getIssuer() != null && !jwtProperties.getIssuer().isBlank()) {
            builder.issuer(jwtProperties.getIssuer());
        }

        return builder.compact();
    }

    public String extractUserId(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public String extractIssuer(String token) {
        return extractClaim(token, Claims::getIssuer);
    }

    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    public boolean isTokenValid(String token, String expectedUserId) {
        try {
            final String userId = extractUserId(token);
            return (userId != null && userId.equals(expectedUserId)) && !isTokenExpired(token);
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public boolean isTokenExpired(String token) {
        return extractExpiration(token).before(new Date());
    }

    public Date extractExpiration(String token) {
        return extractClaim(token, Claims::getExpiration);
    }

    public Claims extractAllClaims(String token) {
        var parserBuilder = Jwts.parser()
                .verifyWith(signingKey);

        if (jwtProperties.getIssuer() != null && !jwtProperties.getIssuer().isBlank()) {
            parserBuilder.requireIssuer(jwtProperties.getIssuer());
        }

        return parserBuilder.build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public long getExpirationInSeconds() {
        return jwtProperties.getExpirationMs() / 1000;
    }

    private static SecretKey initSigningKey(String secret) {
        byte[] keyBytes;
        try {
            keyBytes = Decoders.BASE64.decode(secret);
            if (keyBytes.length < 32) {
                keyBytes = secret.getBytes(StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        }
        return Keys.hmacShaKeyFor(keyBytes);
    }
}
