package com.ticketing.ratelimit;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RateLimitService {
    private final StringRedisTemplate redis;
    private final DefaultRedisScript<List> script;

    public RateLimitService(StringRedisTemplate redis) {
        this.redis = redis;
        this.script = new DefaultRedisScript<>();
        this.script.setResultType(List.class);
        // Atomic sliding-window log with ZSET.
        // Returns: [allowed(1/0), retryAfterMs]
        this.script.setScriptText(
                """
                local key = KEYS[1]
                local now = tonumber(ARGV[1])
                local windowMs = tonumber(ARGV[2])
                local limit = tonumber(ARGV[3])

                local min = now - windowMs
                redis.call('ZREMRANGEBYSCORE', key, 0, min)
                redis.call('ZADD', key, now, tostring(now) .. '-' .. redis.call('INCR', key .. ':seq'))
                local count = redis.call('ZCARD', key)
                redis.call('PEXPIRE', key, windowMs + 1000)
                redis.call('PEXPIRE', key .. ':seq', windowMs + 1000)

                if count <= limit then
                  return {1, 0}
                end

                local oldest = redis.call('ZRANGE', key, 0, 0, 'WITHSCORES')
                if oldest ~= nil and #oldest >= 2 then
                  local oldestScore = tonumber(oldest[2])
                  local retryAfter = (oldestScore + windowMs) - now
                  if retryAfter < 0 then retryAfter = 0 end
                  return {0, retryAfter}
                end
                return {0, windowMs}
                """);
    }

    public RateLimitResult check(String key, long nowMs, long windowMs, int limit) {
        @SuppressWarnings("unchecked")
        List<Object> res = redis.execute(script, List.of(key), String.valueOf(nowMs), String.valueOf(windowMs), String.valueOf(limit));
        if (res == null || res.size() < 2) {
            // Fail-open to avoid turning Redis hiccups into an outage.
            return new RateLimitResult(true, 0);
        }
        boolean allowed = "1".equals(String.valueOf(res.get(0)));
        long retryAfterMs = Long.parseLong(String.valueOf(res.get(1)));
        return new RateLimitResult(allowed, retryAfterMs);
    }
}

