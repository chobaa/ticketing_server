package com.ticketing.metrics;

import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Stores per-runId counters for load-test drill-down.
 *
 * This is intentionally NOT exported as Prometheus labels (high-cardinality).
 * It is used via an API endpoint and via Loki log filtering.
 */
@Service
public class RunScopedMetricsStore {

    private static final long TTL_MS = 6 * 60 * 60 * 1000L; // 6 hours

    public static final class RunCounters {
        public final long createdAtMs = System.currentTimeMillis();
        public final AtomicLong lastTouchedMs = new AtomicLong(System.currentTimeMillis());

        public final AtomicLong queueEntered = new AtomicLong();
        public final AtomicLong admissionIssued = new AtomicLong();
        public final AtomicLong seatLockFailed = new AtomicLong();
        public final AtomicLong reservationExpired = new AtomicLong();
        public final AtomicLong rateLimitRejected = new AtomicLong();
        public final AtomicLong httpRequests = new AtomicLong();

        public final AtomicLong reservationAttempted = new AtomicLong();
        public final AtomicLong reservationSucceeded = new AtomicLong();
        public final AtomicLong reservationFailedInvalidAdmission = new AtomicLong();
        public final AtomicLong reservationFailedSeatNotAvailable = new AtomicLong();
        public final AtomicLong reservationFailedBadSeat = new AtomicLong();

        public final AtomicLong paymentRequested = new AtomicLong();
        public final AtomicLong paymentSucceeded = new AtomicLong();
        public final AtomicLong paymentFailed = new AtomicLong();

        void touch() {
            lastTouchedMs.set(System.currentTimeMillis());
        }
    }

    private final Map<String, RunCounters> byRunId = new ConcurrentHashMap<>();

    public static String currentRunIdOrNull() {
        String rid = MDC.get("runId");
        if (rid == null) return null;
        String t = rid.trim();
        return t.isEmpty() ? null : t;
    }

    public RunCounters getOrCreate(String runId) {
        return byRunId.computeIfAbsent(runId, ignored -> new RunCounters());
    }

    public RunCounters get(String runId) {
        return byRunId.get(runId);
    }

    public void incQueueEntered(String runId) {
        if (runId == null) return;
        RunCounters r = getOrCreate(runId);
        r.touch();
        r.queueEntered.incrementAndGet();
    }

    public void incAdmissionIssued(String runId) {
        if (runId == null) return;
        RunCounters r = getOrCreate(runId);
        r.touch();
        r.admissionIssued.incrementAndGet();
    }

    public void incSeatLockFailed(String runId) {
        if (runId == null) return;
        RunCounters r = getOrCreate(runId);
        r.touch();
        r.seatLockFailed.incrementAndGet();
    }

    public void incReservationExpired(String runId) {
        if (runId == null) return;
        RunCounters r = getOrCreate(runId);
        r.touch();
        r.reservationExpired.incrementAndGet();
    }

    public void incRateLimitRejected(String runId) {
        if (runId == null) return;
        RunCounters r = getOrCreate(runId);
        r.touch();
        r.rateLimitRejected.incrementAndGet();
    }

    public void incHttpRequest(String runId) {
        if (runId == null) return;
        RunCounters r = getOrCreate(runId);
        r.touch();
        r.httpRequests.incrementAndGet();
    }

    public void incReservationAttempted(String runId) {
        if (runId == null) return;
        RunCounters r = getOrCreate(runId);
        r.touch();
        r.reservationAttempted.incrementAndGet();
    }

    public void incReservationSucceeded(String runId) {
        if (runId == null) return;
        RunCounters r = getOrCreate(runId);
        r.touch();
        r.reservationSucceeded.incrementAndGet();
    }

    public void incReservationFailedInvalidAdmission(String runId) {
        if (runId == null) return;
        RunCounters r = getOrCreate(runId);
        r.touch();
        r.reservationFailedInvalidAdmission.incrementAndGet();
    }

    public void incReservationFailedSeatNotAvailable(String runId) {
        if (runId == null) return;
        RunCounters r = getOrCreate(runId);
        r.touch();
        r.reservationFailedSeatNotAvailable.incrementAndGet();
    }

    public void incReservationFailedBadSeat(String runId) {
        if (runId == null) return;
        RunCounters r = getOrCreate(runId);
        r.touch();
        r.reservationFailedBadSeat.incrementAndGet();
    }

    public void incPaymentRequested(String runId) {
        if (runId == null) return;
        RunCounters r = getOrCreate(runId);
        r.touch();
        r.paymentRequested.incrementAndGet();
    }

    public void incPaymentSucceeded(String runId) {
        if (runId == null) return;
        RunCounters r = getOrCreate(runId);
        r.touch();
        r.paymentSucceeded.incrementAndGet();
    }

    public void incPaymentFailed(String runId) {
        if (runId == null) return;
        RunCounters r = getOrCreate(runId);
        r.touch();
        r.paymentFailed.incrementAndGet();
    }

    @Scheduled(fixedDelayString = "${ticketing.run-metrics.cleanup-interval-ms:60000}")
    public void cleanup() {
        long now = System.currentTimeMillis();
        byRunId.entrySet().removeIf(e -> now - e.getValue().lastTouchedMs.get() > TTL_MS);
    }

    public Map<String, Object> snapshot(String runId) {
        RunCounters r = byRunId.get(runId);
        if (r == null) return Map.of("runId", runId, "found", false, "time", Instant.now().toString());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("runId", runId);
        m.put("found", true);
        m.put("time", Instant.now().toString());

        m.put("queueEnteredTotal", r.queueEntered.get());
        m.put("admissionIssuedTotal", r.admissionIssued.get());
        m.put("seatLockFailedTotal", r.seatLockFailed.get());
        m.put("reservationExpiredTotal", r.reservationExpired.get());
        m.put("rateLimitRejectedTotal", r.rateLimitRejected.get());
        m.put("httpServerRequestTotal", r.httpRequests.get());

        m.put("reservationAttemptedTotal", r.reservationAttempted.get());
        m.put("reservationSucceededTotal", r.reservationSucceeded.get());
        m.put("reservationFailedInvalidAdmissionTotal", r.reservationFailedInvalidAdmission.get());
        m.put("reservationFailedSeatNotAvailableTotal", r.reservationFailedSeatNotAvailable.get());
        m.put("reservationFailedBadSeatTotal", r.reservationFailedBadSeat.get());

        m.put("paymentRequestedTotal", r.paymentRequested.get());
        m.put("paymentSucceededTotal", r.paymentSucceeded.get());
        m.put("paymentFailedTotal", r.paymentFailed.get());
        return m;
    }
}

