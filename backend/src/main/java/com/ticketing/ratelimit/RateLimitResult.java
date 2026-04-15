package com.ticketing.ratelimit;

public record RateLimitResult(boolean allowed, long retryAfterMs) {}

