package com.ticketing.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Adds low-cardinality request context to logs (MDC).
 *
 * Intentionally avoids emitting high-cardinality Prometheus labels.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestDebugContextFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestDebugContextFilter.class);
    private final com.ticketing.metrics.RunScopedMetricsStore runScoped;

    public static final String HEADER_RUN_ID = "X-LoadTest-RunId";
    public static final String HEADER_REQUEST_ID = "X-Request-Id";

    public RequestDebugContextFilter(com.ticketing.metrics.RunScopedMetricsStore runScoped) {
        this.runScoped = runScoped;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String runId = request.getHeader(HEADER_RUN_ID);
        String reqId = request.getHeader(HEADER_REQUEST_ID);
        if (reqId == null || reqId.isBlank()) {
            reqId = UUID.randomUUID().toString();
        }
        long startNs = System.nanoTime();
        try {
            MDC.put("reqId", reqId);
            if (runId != null && !runId.isBlank()) {
                MDC.put("runId", runId.trim());
            }
            response.setHeader(HEADER_REQUEST_ID, reqId);
            filterChain.doFilter(request, response);
        } finally {
            long durMs = (System.nanoTime() - startNs) / 1_000_000L;
            // Minimal access log for drill-down (runId/reqId are in MDC pattern).
            if (runId != null && !runId.isBlank()) {
                runScoped.incHttpRequest(runId.trim());
                log.info(
                        "HTTP {} {} -> {} ({}ms)",
                        request.getMethod(),
                        request.getRequestURI(),
                        response.getStatus(),
                        durMs);
            }
            MDC.remove("reqId");
            MDC.remove("runId");
        }
    }
}

