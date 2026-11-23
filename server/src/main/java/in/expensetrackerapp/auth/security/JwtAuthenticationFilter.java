package in.expensetrackerapp.auth.security;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private final JwtService jwtService;

    /** Optional (field-injected so the constructor is unchanged): used for logout / password-change revocation. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.data.mongodb.core.MongoTemplate mongoTemplate;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return path.startsWith("/api/auth/");
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        final String authHeader = request.getHeader("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        final String jwt = authHeader.substring(7);

        try {
            final String userId = jwtService.extractUserId(jwt);

            if (userId != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                if (jwtService.isTokenValid(jwt, userId) && !revoked(jwt, userId)) {
                    UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                            userId,
                            null,
                            Collections.emptyList()
                    );
                    authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authToken);
                }
            }
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("JWT validation failed: {}", e.getMessage());
            // Clear context if invalid token
            SecurityContextHolder.clearContext();
        }

        filterChain.doFilter(request, response);
    }

    private boolean revoked(String jwt, String userId) {
        if (mongoTemplate == null) {
            return false;
        }
        try {
            var q = org.springframework.data.mongodb.core.query.Query.query(
                    org.springframework.data.mongodb.core.query.Criteria.where("_id").is(userId));
            q.fields().include("tokenVersion");
            var user = mongoTemplate.findOne(q, in.expensetrackerapp.user.model.User.class);
            if (user == null) {
                return true;                                   // deleted account: token is no longer valid
            }
            Integer claim = jwtService.extractClaim(jwt, c -> c.get("tv", Integer.class));
            return (claim == null ? 0 : claim) != user.getTokenVersion();
        } catch (RuntimeException e) {
            log.warn("Token revocation check failed: {}", e.getMessage());
            return false;
        }
    }
}
