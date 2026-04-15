package com.ticketing.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.stereotype.Service;

@Service
public class RedisMonitoringService {

    private final StringRedisTemplate redis;

    public RedisMonitoringService(MeterRegistry registry, StringRedisTemplate redis) {
        this.redis = redis;
        Gauge.builder("ticketing.redis.up", this, RedisMonitoringService::ping)
                .description("1 if Redis PING succeeds, else 0")
                .register(registry);
    }

    /**
     * Called by gauge on scrape; keep it cheap and fail-open.
     */
    private double ping() {
        try {
            String pong = redis.execute((RedisCallback<String>) c -> c.ping());
            return "PONG".equalsIgnoreCase(pong) ? 1.0 : 0.0;
        } catch (Exception ignored) {
            return 0.0;
        }
    }
}

