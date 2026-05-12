package com.ticketing.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class BusinessMetrics {

    private final MeterRegistry registry;
    private final RunScopedMetricsStore runScoped;

    private final Counter paymentRequested;
    private final Counter paymentSucceeded;
    private final Counter paymentFailed;
    private final Counter paymentRequestDroppedMissingReservation;
    private final Counter paymentRequestSkippedDuplicate;
    private final Counter paymentSettleSkippedAlreadyTerminal;
    private final Counter paymentWorkerSleepMsTotal;
    private final java.util.concurrent.atomic.AtomicInteger paymentWorkersSleeping;

    private final Counter queueEntered;
    private final Counter admissionIssued;
    private final Map<String, Counter> rateLimitRejected = new ConcurrentHashMap<>();
    private final Counter seatLockFailed;
    private final Counter reservationExpired;
    private final Counter reservationAttempted;
    private final Counter reservationSucceeded;
    private final Counter reservationFailedInvalidAdmission;
    private final Counter reservationFailedSeatNotAvailable;
    private final Counter reservationFailedBadSeat;

    private final ClusterBusinessMetricsBridge clusterCounters;

    public BusinessMetrics(MeterRegistry registry, ClusterBusinessMetricsBridge clusterCounters, RunScopedMetricsStore runScoped) {
        this.registry = registry;
        this.clusterCounters = clusterCounters;
        this.runScoped = runScoped;
        this.paymentRequested =
                Counter.builder("ticketing.payment.requested.total")
                        .description("Total count of payment requested events produced")
                        .register(registry);
        this.paymentSucceeded =
                Counter.builder("ticketing.payment.succeeded.total")
                        .description("Total count of payment success settlements")
                        .register(registry);
        this.paymentFailed =
                Counter.builder("ticketing.payment.failed.total")
                        .description("Total count of payment failure settlements")
                        .register(registry);
        this.paymentRequestDroppedMissingReservation =
                Counter.builder("ticketing.payment.request.dropped.missing_reservation.total")
                        .description("Payment requests dropped because reservation row does not exist")
                        .register(registry);
        this.paymentRequestSkippedDuplicate =
                Counter.builder("ticketing.payment.request.skipped.duplicate.total")
                        .description("Payment requests skipped because payment already exists (duplicate)")
                        .register(registry);
        this.paymentSettleSkippedAlreadyTerminal =
                Counter.builder("ticketing.payment.settle.skipped.already_terminal.total")
                        .description("Payment settle events skipped because reservation/seat already terminal")
                        .register(registry);

        this.paymentWorkerSleepMsTotal =
                Counter.builder("ticketing.payment.worker.sleep.ms.total")
                        .description("Total milliseconds slept by payment worker simulation")
                        .register(registry);
        this.paymentWorkersSleeping = new java.util.concurrent.atomic.AtomicInteger(0);

        io.micrometer.core.instrument.Gauge.builder(
                        "ticketing.payment.inflight",
                        registry,
                        r -> Math.max(0.0, paymentRequested.count() - (paymentSucceeded.count() + paymentFailed.count())))
                .description("Estimated in-flight payments = requested - (succeeded + failed)")
                .register(registry);

        io.micrometer.core.instrument.Gauge.builder(
                        "ticketing.payment.worker.sleeping",
                        paymentWorkersSleeping,
                        java.util.concurrent.atomic.AtomicInteger::get)
                .description("Current number of payment workers sleeping (simulation dwell)")
                .register(registry);

        this.queueEntered =
                Counter.builder("ticketing.queue.entered.total")
                        .description("Total count of queue enter attempts (joinQueue)")
                        .register(registry);
        this.admissionIssued =
                Counter.builder("ticketing.queue.admission.issued.total")
                        .description("Total count of admission tokens issued by scheduler")
                        .register(registry);
        this.seatLockFailed =
                Counter.builder("ticketing.reservation.seat_lock.failed.total")
                        .description("Total count of reservation attempts failed due to seat lock contention")
                        .register(registry);

        this.reservationExpired =
                Counter.builder("ticketing.reservation.expired.total")
                        .description("Total count of reservations expired (TTL rollback)")
                        .register(registry);

        this.reservationAttempted =
                Counter.builder("ticketing.reservation.attempted.total")
                        .description("Total count of reservation attempts (reserve endpoint called)")
                        .register(registry);
        this.reservationSucceeded =
                Counter.builder("ticketing.reservation.succeeded.total")
                        .description("Total count of reservations created successfully")
                        .register(registry);
        this.reservationFailedInvalidAdmission =
                Counter.builder("ticketing.reservation.failed.invalid_admission.total")
                        .description("Reservation attempts rejected due to invalid/missing admission token")
                        .register(registry);
        this.reservationFailedSeatNotAvailable =
                Counter.builder("ticketing.reservation.failed.seat_not_available.total")
                        .description("Reservation attempts rejected because the seat is not AVAILABLE")
                        .register(registry);
        this.reservationFailedBadSeat =
                Counter.builder("ticketing.reservation.failed.bad_seat.total")
                        .description("Reservation attempts rejected because seat not found or not in event")
                        .register(registry);
    }

    public void incPaymentRequested() {
        paymentRequested.increment();
        clusterCounters.increment(ClusterBusinessMetricsBridge.SUFFIX_PAYMENT_REQUESTED, 1);
        runScoped.incPaymentRequested(RunScopedMetricsStore.currentRunIdOrNull());
    }

    public void incPaymentSucceeded() {
        paymentSucceeded.increment();
        clusterCounters.increment(ClusterBusinessMetricsBridge.SUFFIX_PAYMENT_SUCCEEDED, 1);
        runScoped.incPaymentSucceeded(RunScopedMetricsStore.currentRunIdOrNull());
    }

    public void incPaymentFailed() {
        paymentFailed.increment();
        clusterCounters.increment(ClusterBusinessMetricsBridge.SUFFIX_PAYMENT_FAILED, 1);
        runScoped.incPaymentFailed(RunScopedMetricsStore.currentRunIdOrNull());
    }

    public void incPaymentRequestDroppedMissingReservation() {
        paymentRequestDroppedMissingReservation.increment();
        clusterCounters.increment(ClusterBusinessMetricsBridge.SUFFIX_PAYMENT_DROPPED, 1);
    }

    public void incPaymentRequestSkippedDuplicate() {
        paymentRequestSkippedDuplicate.increment();
        clusterCounters.increment(ClusterBusinessMetricsBridge.SUFFIX_PAYMENT_SKIPPED_DUPLICATE, 1);
    }

    public void incPaymentSettleSkippedAlreadyTerminal() {
        paymentSettleSkippedAlreadyTerminal.increment();
        clusterCounters.increment(ClusterBusinessMetricsBridge.SUFFIX_PAYMENT_SETTLE_SKIPPED_TERMINAL, 1);
    }

    public void addPaymentWorkerSleepMs(long ms) {
        if (ms <= 0) return;
        paymentWorkerSleepMsTotal.increment(ms);
        clusterCounters.increment(ClusterBusinessMetricsBridge.SUFFIX_PAYMENT_WORKER_SLEEP_MS, ms);
    }

    public void incPaymentWorkersSleeping() {
        paymentWorkersSleeping.incrementAndGet();
    }

    public void decPaymentWorkersSleeping() {
        paymentWorkersSleeping.decrementAndGet();
    }

    public void incQueueEntered() {
        queueEntered.increment();
        clusterCounters.increment(ClusterBusinessMetricsBridge.SUFFIX_QUEUE_ENTERED, 1);
        runScoped.incQueueEntered(RunScopedMetricsStore.currentRunIdOrNull());
    }

    public void incAdmissionIssued() {
        admissionIssued.increment();
        clusterCounters.increment(ClusterBusinessMetricsBridge.SUFFIX_ADMISSION_ISSUED, 1);
        runScoped.incAdmissionIssued(RunScopedMetricsStore.currentRunIdOrNull());
    }

    public void incSeatLockFailed() {
        seatLockFailed.increment();
        clusterCounters.increment(ClusterBusinessMetricsBridge.SUFFIX_SEAT_LOCK_FAILED, 1);
        runScoped.incSeatLockFailed(RunScopedMetricsStore.currentRunIdOrNull());
    }

    public void incReservationAttempted() {
        reservationAttempted.increment();
        runScoped.incReservationAttempted(RunScopedMetricsStore.currentRunIdOrNull());
    }

    public void incReservationSucceeded() {
        reservationSucceeded.increment();
        runScoped.incReservationSucceeded(RunScopedMetricsStore.currentRunIdOrNull());
    }

    public void incReservationFailedInvalidAdmission() {
        reservationFailedInvalidAdmission.increment();
        runScoped.incReservationFailedInvalidAdmission(RunScopedMetricsStore.currentRunIdOrNull());
    }

    public void incReservationFailedSeatNotAvailable() {
        reservationFailedSeatNotAvailable.increment();
        runScoped.incReservationFailedSeatNotAvailable(RunScopedMetricsStore.currentRunIdOrNull());
    }

    public void incReservationFailedBadSeat() {
        reservationFailedBadSeat.increment();
        runScoped.incReservationFailedBadSeat(RunScopedMetricsStore.currentRunIdOrNull());
    }

    public void incReservationExpired() {
        reservationExpired.increment();
        clusterCounters.increment(ClusterBusinessMetricsBridge.SUFFIX_RESERVATION_EXPIRED, 1);
        runScoped.incReservationExpired(RunScopedMetricsStore.currentRunIdOrNull());
    }

    public void incRateLimitRejected(String scope) {
        counter(rateLimitRejected, "ticketing.ratelimit.rejected.total", "scope", safe(scope)).increment();
        clusterCounters.increment(ClusterBusinessMetricsBridge.SUFFIX_RATELIMIT_REJECTED, 1);
        runScoped.incRateLimitRejected(RunScopedMetricsStore.currentRunIdOrNull());
    }

    private Counter counter(Map<String, Counter> cache, String name, String tagKey, String tagValue) {
        String key = name + "|" + tagKey + "=" + tagValue;
        return cache.computeIfAbsent(
                key,
                ignored ->
                        Counter.builder(name)
                                .description("Business-level counters")
                                .tag(tagKey, tagValue)
                                .register(registry));
    }

    private static String safe(String s) {
        if (s == null) return "unknown";
        String t = s.trim();
        return t.isEmpty() ? "unknown" : t;
    }
}

