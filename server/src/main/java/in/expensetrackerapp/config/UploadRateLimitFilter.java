package in.expensetrackerapp.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 6 upload rate limit: at most N POST /api/documents (and reprocess) per window per bearer token (in memory,
 * per instance). Keyed by the Authorization header so it runs before authentication; invalid tokens are rejected
 * later by Spring Security anyway.
 */
@Component
@Order(-200)
public class UploadRateLimitFilter extends OncePerRequestFilter {

    private final int maxRequests;
    private final long windowMillis;
    private final Clock clock;
    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    public UploadRateLimitFilter(@Value("${application.uploads.max-per-window:20}") int maxRequests,
                                 @Value("${application.uploads.window-seconds:60}") long windowSeconds,
                                 Clock clock) {
        this.maxRequests = maxRequests;
        this.windowMillis = windowSeconds * 1000;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return !"POST".equals(request.getMethod())
                || !(path.equals("/api/documents") || path.matches("/api/documents/[^/]+/reprocess"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = request.getHeader("Authorization");
        if (key == null) {
            chain.doFilter(request, response);
            return;
        }
        long now = clock.millis();
        Deque<Long> q = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        long retryAfter = 0;
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() > windowMillis) q.pollFirst();
            if (q.size() >= maxRequests) {
                retryAfter = Math.max(1, (windowMillis - (now - q.peekFirst())) / 1000);
            } else {
                q.addLast(now);
            }
        }
        if (hits.size() > 10_000) hits.entrySet().removeIf(e -> e.getValue().isEmpty());
        if (retryAfter > 0) {
            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(retryAfter));
            response.setContentType("application/json");
            response.getWriter().write("{\"status\":429,\"error\":\"Too Many Requests\",\"message\":\"Too many uploads. Try again in "
                    + retryAfter + " seconds\",\"path\":\"" + request.getRequestURI().replaceAll("[^A-Za-z0-9/_-]", "") + "\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
