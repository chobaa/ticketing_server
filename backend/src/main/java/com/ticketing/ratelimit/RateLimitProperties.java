package com.ticketing.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ticketing.rate-limit")
public record RateLimitProperties(
        boolean enabled, Scope ip, Scope user) {
    public record Scope(int requests, long windowMs) {}
}

