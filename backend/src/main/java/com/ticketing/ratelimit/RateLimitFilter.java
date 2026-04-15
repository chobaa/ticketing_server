package com.ticketing.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Component
public class RateLimitFilter extends OncePerRequestFilter {
    private final RateLimitProperties props;
    private final RateLimitService service;

    public RateLimitFilter(RateLimitProperties props, RateLimitService service) {
        this.props = props;
        this.service = service;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!props.enabled()) return true;
        String path = request.getRequestURI();
        // Avoid breaking metrics/WS and preflight.
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) return true;
        if (path.startsWith("/actuator/")) return true;
        if (path.startsWith("/ws/")) return true;
        // Only protect API surface.
        return !path.startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        long now = System.currentTimeMillis();

        String ip = clientIp(request);
        if (ip != null && !ip.isBlank()) {
            RateLimitResult ipRes = service.check(
                    "rl:ip:{" + ip + "}",
                    now,
                    props.ip().windowMs(),
                    props.ip().requests());
            if (!ipRes.allowed()) {
                reject(response, ipRes.retryAfterMs());
                return;
            }
        }

        Long userId = authenticatedUserId();
        if (userId != null) {
            RateLimitResult userRes = service.check(
                    "rl:user:{" + userId + "}",
                    now,
                    props.user().windowMs(),
                    props.user().requests());
            if (!userRes.allowed()) {
                reject(response, userRes.retryAfterMs());
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private static Long authenticatedUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return null;
        Object principal = auth.getPrincipal();
        if (principal instanceof Long l) return l;
        if (principal instanceof String s) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            // first IP is the original client.
            int comma = xff.indexOf(',');
            return (comma >= 0 ? xff.substring(0, comma) : xff).trim();
        }
        String xri = request.getHeader("X-Real-IP");
        if (xri != null && !xri.isBlank()) return xri.trim();
        return request.getRemoteAddr();
    }

    private static void reject(HttpServletResponse response, long retryAfterMs) throws IOException {
        response.setStatus(429);
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(Math.max(1, Duration.ofMillis(retryAfterMs).toSeconds())));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"error\":\"rate_limited\"}");
    }
}

