package com.ticketing.metrics;

/**
 * Per load-test run tuning (Scenario C rate limit, Scenario D zombie TTL, etc.).
 * Stored in Redis by {@code runId}.
 */
public record LoadTestRunProfile(
        Integer holdTtlSeconds,
        boolean skipPayment,
        Integer rateLimitUserRequests,
        Long rateLimitUserWindowMs,
        Integer rateLimitIpRequests,
        Long rateLimitIpWindowMs) {

    /** Scenario D: 60s hold, no auto-payment, global rate limits unchanged. */
    public static LoadTestRunProfile zombieTtl(int holdTtlSeconds) {
        return new LoadTestRunProfile(Math.max(1, holdTtlSeconds), true, null, null, null, null);
    }

    /**
     * Scenario C: strict sliding window (matches {@code application.yml} defaults).
     * nGrinder agents share one IP, so both IP and per-user buckets should reject.
     */
    public static LoadTestRunProfile retryStormRateLimit() {
        return new LoadTestRunProfile(null, false, 5, 1_000L, 10, 1_000L);
    }

    public boolean hasRateLimitOverride() {
        return rateLimitUserRequests != null || rateLimitIpRequests != null;
    }
}
