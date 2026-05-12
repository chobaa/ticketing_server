package com.ticketing.metrics;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Optional Redis-backed totals for selected business counters so multiple app instances can expose
 * consistent numbers on {@code /api/dashboard/business-metrics} without an external Prometheus sum.
 */
@Slf4j
@Component
public class ClusterBusinessMetricsBridge {

    /** Versioned prefix so we can reset the whole namespace if the schema changes. */
    public static final String KEY_PREFIX = "ticketing:metrics:cluster:v1:";

    public static final String SUFFIX_PAYMENT_REQUESTED = "payment.requested";
    public static final String SUFFIX_PAYMENT_SUCCEEDED = "payment.succeeded";
    public static final String SUFFIX_PAYMENT_FAILED = "payment.failed";
    public static final String SUFFIX_PAYMENT_DROPPED = "payment.dropped.missing_reservation";
    public static final String SUFFIX_PAYMENT_SKIPPED_DUPLICATE = "payment.skipped.duplicate";
    public static final String SUFFIX_PAYMENT_SETTLE_SKIPPED_TERMINAL = "payment.settle.skipped.already_terminal";
    public static final String SUFFIX_PAYMENT_WORKER_SLEEP_MS = "payment.worker.sleep.ms";
    public static final String SUFFIX_QUEUE_ENTERED = "queue.entered";
    public static final String SUFFIX_ADMISSION_ISSUED = "queue.admission.issued";
    public static final String SUFFIX_SEAT_LOCK_FAILED = "reservation.seat_lock.failed";
    public static final String SUFFIX_RESERVATION_EXPIRED = "reservation.expired";
    public static final String SUFFIX_RATELIMIT_REJECTED = "ratelimit.rejected";

    private final StringRedisTemplate redis;

    @Value("${ticketing.metrics.cluster-counters.enabled:false}")
    private boolean enabled;

    public ClusterBusinessMetricsBridge(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void increment(String suffix, long delta) {
        if (!enabled || delta == 0) {
            return;
        }
        try {
            redis.opsForValue().increment(KEY_PREFIX + suffix, delta);
        } catch (Exception e) {
            log.warn("cluster counter increment failed suffix={} delta={}: {}", suffix, delta, e.toString());
        }
    }

    /**
     * @return {@link Long#MIN_VALUE} if feature disabled; otherwise parsed long (missing key = 0), or
     *     {@link Long#MAX_VALUE} on read error so callers can fall back to local Micrometer.
     */
    public long readLong(String suffix) {
        if (!enabled) {
            return Long.MIN_VALUE;
        }
        try {
            String v = redis.opsForValue().get(KEY_PREFIX + suffix);
            if (v == null) {
                return 0L;
            }
            return Long.parseLong(v.trim());
        } catch (Exception e) {
            log.warn("cluster counter read failed suffix={}: {}", suffix, e.toString());
            return Long.MAX_VALUE;
        }
    }
}
