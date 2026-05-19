package com.ticketing.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.ticketing.logging.RequestDebugContextFilter;
import com.ticketing.metrics.LoadTestRunAttributionService;
import com.ticketing.metrics.LoadTestRunProfile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Component
public class RateLimitFilter extends OncePerRequestFilter {
    private final RateLimitProperties props;
    private final RateLimitService service;
    private final com.ticketing.metrics.BusinessMetrics businessMetrics;
    private final LoadTestRunAttributionService loadTestRunAttribution;

    public RateLimitFilter(
            RateLimitProperties props,
            RateLimitService service,
            com.ticketing.metrics.BusinessMetrics businessMetrics,
            LoadTestRunAttributionService loadTestRunAttribution) {
        this.props = props;
        this.service = service;
        this.businessMetrics = businessMetrics;
        this.loadTestRunAttribution = loadTestRunAttribution;
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
        String path = request.getRequestURI();
        LoadTestRunProfile runProfile = resolveRunProfile(request);

        String ip = clientIp(request);
        if (ip != null && !ip.isBlank()) {
            // nGrinder (and similar) agents share one public IP while creating many users in parallel.
            // Keep the default strict /api bucket, but use a separate Redis key with a much higher ceiling
            // for auth bootstrap endpoints only.
            boolean authBootstrap =
                    "POST".equalsIgnoreCase(request.getMethod())
                            && (path.endsWith("/api/auth/register") || path.endsWith("/api/auth/login"));
            long ipWindow =
                    runProfile != null && runProfile.rateLimitIpWindowMs() != null
                            ? runProfile.rateLimitIpWindowMs()
                            : props.ip().windowMs();
            int ipBudget =
                    authBootstrap
                            ? Math.max(5_000, Math.min(100_000, props.ip().requests() * 500))
                            : runProfile != null && runProfile.rateLimitIpRequests() != null
                                    ? runProfile.rateLimitIpRequests()
                                    : props.ip().requests();
            String ipKey = authBootstrap ? ("rl:ip:auth:{" + ip + "}") : ("rl:ip:{" + ip + "}");
            RateLimitResult ipRes = service.check(ipKey, now, ipWindow, ipBudget);
            if (!ipRes.allowed()) {
                businessMetrics.incRateLimitRejected("ip");
                reject(response, ipRes.retryAfterMs());
                return;
            }
        }

        Long userId = authenticatedUserId();
        if (userId != null) {
            long userWindow =
                    runProfile != null && runProfile.rateLimitUserWindowMs() != null
                            ? runProfile.rateLimitUserWindowMs()
                            : props.user().windowMs();
            int userBudget =
                    runProfile != null && runProfile.rateLimitUserRequests() != null
                            ? runProfile.rateLimitUserRequests()
                            : props.user().requests();
            RateLimitResult userRes = service.check(
                    "rl:user:{" + userId + "}",
                    now,
                    userWindow,
                    userBudget);
            if (!userRes.allowed()) {
                businessMetrics.incRateLimitRejected("user");
                reject(response, userRes.retryAfterMs());
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private LoadTestRunProfile resolveRunProfile(HttpServletRequest request) {
        String runId = request.getHeader(RequestDebugContextFilter.HEADER_RUN_ID);
        if (runId == null || runId.isBlank()) {
            runId = MDC.get("runId");
        }
        if (runId == null || runId.isBlank()) {
            return null;
        }
        LoadTestRunProfile profile = loadTestRunAttribution.resolveProfile(runId.trim());
        if (profile == null || !profile.hasRateLimitOverride()) {
            return null;
        }
        return profile;
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

