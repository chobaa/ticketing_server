package com.ticketing.metrics;



import lombok.RequiredArgsConstructor;

import org.redisson.api.RBucket;

import org.redisson.api.RedissonClient;

import org.springframework.beans.factory.annotation.Value;

import org.springframework.stereotype.Service;



import java.util.concurrent.TimeUnit;



/**

 * Links load-test {@code runId} (HTTP header) to later background work (admission scheduler)

 * where {@link org.slf4j.MDC} is not available.

 */

@Service

@RequiredArgsConstructor

public class LoadTestRunAttributionService {



    private final RedissonClient redissonClient;



    @Value("${ticketing.queue.token-ttl-seconds:600}")

    private int ttlSeconds;



    private static String key(long eventId, long userId) {

        return "ticketing:loadtest:run:attrib:v1:" + eventId + ":" + userId;

    }



    private static String profileKey(String runId) {

        return "ticketing:loadtest:run:profile:v1:" + runId;

    }



    public void remember(long eventId, long userId, String runId) {

        if (runId == null || runId.isBlank()) {

            return;

        }

        RBucket<String> bucket = redissonClient.getBucket(key(eventId, userId));

        bucket.set(runId.trim(), ttlSeconds, TimeUnit.SECONDS);

    }



    public String resolve(long eventId, long userId) {

        RBucket<String> bucket = redissonClient.getBucket(key(eventId, userId));

        String v = bucket.get();

        if (v == null || v.isBlank()) {

            return null;

        }

        return v.trim();

    }



    public void rememberProfile(String runId, LoadTestRunProfile profile) {

        if (runId == null || runId.isBlank() || profile == null) {

            return;

        }

        String encoded = encodeProfile(profile);

        RBucket<String> bucket = redissonClient.getBucket(profileKey(runId.trim()));

        bucket.set(encoded, ttlSeconds, TimeUnit.SECONDS);

    }



    public LoadTestRunProfile resolveProfile(String runId) {

        if (runId == null || runId.isBlank()) {

            return null;

        }

        RBucket<String> bucket = redissonClient.getBucket(profileKey(runId.trim()));

        String v = bucket.get();

        if (v == null || v.isBlank()) {

            return null;

        }

        return decodeProfile(v.trim());

    }



    public Integer resolveHoldTtlSeconds(long eventId, long userId) {

        String runId = resolve(eventId, userId);

        if (runId == null) {

            return null;

        }

        LoadTestRunProfile profile = resolveProfile(runId);

        return profile == null ? null : profile.holdTtlSeconds();

    }



    public boolean shouldSkipPayment(long eventId, long userId) {

        String runId = resolve(eventId, userId);

        if (runId == null) {

            return false;

        }

        LoadTestRunProfile profile = resolveProfile(runId);

        return profile != null && profile.skipPayment();

    }



    public void purgeEvent(long eventId) {

        try {

            redissonClient.getKeys().deleteByPattern("ticketing:loadtest:run:attrib:v1:" + eventId + ":*");

        } catch (Exception ignored) {

        }

    }



    private static String encodeProfile(LoadTestRunProfile p) {

        return "v2:"

                + nullToZero(p.holdTtlSeconds())

                + ":"

                + (p.skipPayment() ? "1" : "0")

                + ":"

                + nullToZero(p.rateLimitUserRequests())

                + ":"

                + nullToZero(p.rateLimitUserWindowMs())

                + ":"

                + nullToZero(p.rateLimitIpRequests())

                + ":"

                + nullToZero(p.rateLimitIpWindowMs());

    }



    private static LoadTestRunProfile decodeProfile(String v) {

        if (v.startsWith("v2:")) {

            String[] parts = v.substring(3).split(":");

            if (parts.length != 6) {

                return null;

            }

            try {

                Integer hold = zeroToNull(Integer.parseInt(parts[0].trim()));

                boolean skip = "1".equals(parts[1].trim());

                Integer uReq = zeroToNull(Integer.parseInt(parts[2].trim()));

                Long uWin = zeroToNullLong(Long.parseLong(parts[3].trim()));

                Integer iReq = zeroToNull(Integer.parseInt(parts[4].trim()));

                Long iWin = zeroToNullLong(Long.parseLong(parts[5].trim()));

                return new LoadTestRunProfile(hold, skip, uReq, uWin, iReq, iWin);

            } catch (NumberFormatException ignored) {

                return null;

            }

        }

        // Legacy v1: holdSec|skipPayment

        String[] parts = v.split("\\|", 2);

        if (parts.length != 2) {

            return null;

        }

        try {

            int holdSec = Integer.parseInt(parts[0].trim());

            boolean skipPayment = "1".equals(parts[1].trim());

            if (holdSec < 1) {

                return null;

            }

            return LoadTestRunProfile.zombieTtl(holdSec);

        } catch (NumberFormatException ignored) {

            return null;

        }

    }



    private static int nullToZero(Integer v) {

        return v == null ? 0 : v;

    }



    private static long nullToZero(Long v) {

        return v == null ? 0L : v;

    }



    private static Integer zeroToNull(int v) {

        return v < 1 ? null : v;

    }



    private static Long zeroToNullLong(long v) {

        return v < 1L ? null : v;

    }

}


