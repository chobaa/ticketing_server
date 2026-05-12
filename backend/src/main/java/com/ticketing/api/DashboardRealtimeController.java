package com.ticketing.api;

import com.ticketing.metrics.ClusterBusinessMetricsBridge;
import com.ticketing.metrics.MetricsSnapshotService;
import com.ticketing.payment.PaymentRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.search.Search;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardRealtimeController {

    private final MetricsSnapshotService metricsSnapshotService;
    private final MeterRegistry meterRegistry;
    private final com.ticketing.payment.PaymentQueueMaintenanceService paymentQueueMaintenanceService;
    private final PaymentRepository paymentRepository;
    private final com.ticketing.metrics.IntegrityRepairService integrityRepairService;
    private final ClusterBusinessMetricsBridge clusterCounters;
    private final com.ticketing.metrics.RunScopedMetricsStore runScoped;

    /** HTTP fallback for real-time snapshot when WebSocket isn't available. */
    @GetMapping("/realtime")
    public ResponseEntity<Map<String, Object>> realtime() {
        return ResponseEntity.ok(metricsSnapshotService.buildSnapshot());
    }

    @GetMapping("/business-metrics")
    public ResponseEntity<Map<String, Object>> businessMetrics() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("clusterCountersEnabled", clusterCounters.isEnabled());

        // requested := issued-to-worker-queue count (not "event created")
        double requested =
                clusterOrLocal(
                        ClusterBusinessMetricsBridge.SUFFIX_PAYMENT_REQUESTED,
                        "ticketing.payment.requested.total");
        double succeeded =
                clusterOrLocal(
                        ClusterBusinessMetricsBridge.SUFFIX_PAYMENT_SUCCEEDED,
                        "ticketing.payment.succeeded.total");
        double failed =
                clusterOrLocal(
                        ClusterBusinessMetricsBridge.SUFFIX_PAYMENT_FAILED, "ticketing.payment.failed.total");
        double dropped =
                clusterOrLocal(
                        ClusterBusinessMetricsBridge.SUFFIX_PAYMENT_DROPPED,
                        "ticketing.payment.request.dropped.missing_reservation.total");
        double duplicate =
                clusterOrLocal(
                        ClusterBusinessMetricsBridge.SUFFIX_PAYMENT_SKIPPED_DUPLICATE,
                        "ticketing.payment.request.skipped.duplicate.total");
        double settleAlreadyTerminal =
                clusterOrLocal(
                        ClusterBusinessMetricsBridge.SUFFIX_PAYMENT_SETTLE_SKIPPED_TERMINAL,
                        "ticketing.payment.settle.skipped.already_terminal.total");

        // 큐대기: rabbit queue depth (not yet consumed by worker)
        double queueDepth = gauge("ticketing.payment.queue.depth");

        // Live DB count (scheduled integrity gauge can lag seconds behind counters during load).
        double processing = paymentRepository.countByStatus("PROCESSING");

        // If the pipeline is consistent, this should represent work-in-progress (WIP):
        // wip = requested - (success + fail + dropped + duplicate)
        double wipFromCounters = requested - (succeeded + failed + dropped + duplicate);

        // When "requested" means "issued to worker queue", this should stay ~0 once we account for WIP:
        // requested ≈ (success + fail) + (processing + queueDepth) + dropped + duplicate
        // Do NOT add settleAlreadyTerminal: it counts duplicate Kafka settlement deliveries that do not
        // correspond to an extra bridge "requested" tick (same logical payment).
        double expectedRequested =
                (succeeded + failed) + processing + queueDepth + dropped + duplicate;
        double mismatch = requested - expectedRequested;

        double inflight = Math.max(0.0, processing + queueDepth);
        // Should match (processing + queueDepth + unacked) when counters and gauges align.
        double wipDerived = wipFromCounters;
        double sleepMsTotal =
                clusterOrLocal(
                        ClusterBusinessMetricsBridge.SUFFIX_PAYMENT_WORKER_SLEEP_MS,
                        "ticketing.payment.worker.sleep.ms.total");

        m.put("paymentRequestedTotal", requested);
        m.put("paymentSucceededTotal", succeeded);
        m.put("paymentFailedTotal", failed);
        m.put("paymentSettledTotal", succeeded + failed);
        m.put("paymentProcessing", processing);
        m.put("paymentQueueDepth", queueDepth);
        m.put("paymentInflight", inflight); // queueDepth + processing
        m.put("paymentDroppedMissingReservationTotal", dropped);
        m.put("paymentSkippedDuplicateTotal", duplicate);
        m.put("paymentSettleSkippedAlreadyTerminalTotal", settleAlreadyTerminal);
        m.put("paymentRequestedExpectedTotal", expectedRequested);
        m.put("paymentRequestedMismatch", mismatch);
        m.put("paymentWipFromCounters", wipFromCounters);
        m.put("paymentWipDerived", Math.max(0.0, wipDerived));
        m.put(
                "paymentWipDerivedVsObserved",
                Math.max(0.0, wipDerived) - Math.max(0.0, processing + queueDepth));
        m.put("paymentWorkersSleeping", gauge("ticketing.payment.worker.sleeping"));
        m.put("paymentWorkerSleepMsTotal", sleepMsTotal);

        // Scenario-oriented cumulative signals (Micrometer counters; absent until first increment).
        m.put(
                "queueEnteredTotal",
                clusterOrLocal(ClusterBusinessMetricsBridge.SUFFIX_QUEUE_ENTERED, "ticketing.queue.entered.total"));
        m.put(
                "admissionIssuedTotal",
                clusterOrLocal(
                        ClusterBusinessMetricsBridge.SUFFIX_ADMISSION_ISSUED,
                        "ticketing.queue.admission.issued.total"));
        m.put(
                "seatLockFailedTotal",
                clusterOrLocal(
                        ClusterBusinessMetricsBridge.SUFFIX_SEAT_LOCK_FAILED,
                        "ticketing.reservation.seat_lock.failed.total"));
        m.put(
                "reservationExpiredTotal",
                clusterOrLocal(
                        ClusterBusinessMetricsBridge.SUFFIX_RESERVATION_EXPIRED,
                        "ticketing.reservation.expired.total"));
        m.put(
                "rateLimitRejectedTotal",
                clusterOrLocal(
                        ClusterBusinessMetricsBridge.SUFFIX_RATELIMIT_REJECTED,
                        "ticketing.ratelimit.rejected.total"));

        // Reservation funnel (coarse totals; used to explain where joinQueue traffic went).
        // NOTE: these are local (not cluster-summed) today.
        m.put("reservationAttemptedTotal", counter("ticketing.reservation.attempted.total"));
        m.put("reservationSucceededTotal", counter("ticketing.reservation.succeeded.total"));
        m.put(
                "reservationFailedInvalidAdmissionTotal",
                counter("ticketing.reservation.failed.invalid_admission.total"));
        m.put(
                "reservationFailedSeatNotAvailableTotal",
                counter("ticketing.reservation.failed.seat_not_available.total"));
        m.put("reservationFailedBadSeatTotal", counter("ticketing.reservation.failed.bad_seat.total"));

        // Cumulative HTTP requests handled (useful for read-heavy scenarios like Scenario C).
        Timer httpTimer = Search.in(meterRegistry).name("http.server.requests").timer();
        m.put("httpServerRequestTotal", httpTimer == null ? 0.0 : (double) httpTimer.count());

        m.put("time", java.time.Instant.now().toString());
        return ResponseEntity.ok(m);
    }

    /** Per-runId aggregated counters for load-test drill-down (not Prometheus labels). */
    @GetMapping("/run-metrics")
    public ResponseEntity<Map<String, Object>> runMetrics(@RequestParam String runId) {
        if (runId == null || runId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "runId is required"));
        }
        return ResponseEntity.ok(runScoped.snapshot(runId.trim()));
    }

    @org.springframework.web.bind.annotation.PostMapping("/integrity/repair-seats")
    public ResponseEntity<Map<String, Object>> repairSeatStatus() {
        Map<String, Object> out = new LinkedHashMap<>(integrityRepairService.repairSeatStatusFromReservations());
        out.put("time", java.time.Instant.now().toString());
        return ResponseEntity.ok(out);
    }

    @org.springframework.web.bind.annotation.PostMapping("/integrity/repair-processing-payments")
    public ResponseEntity<Map<String, Object>> repairProcessingPayments() {
        Map<String, Object> out =
                new LinkedHashMap<>(integrityRepairService.repairStaleProcessingPayments());
        out.put("time", java.time.Instant.now().toString());
        return ResponseEntity.ok(out);
    }

    @org.springframework.web.bind.annotation.PostMapping("/payment-queue/purge")
    public ResponseEntity<Map<String, Object>> purgePaymentQueue() {
        int purged = paymentQueueMaintenanceService.purgePaymentQueueReadyMessages();
        long depth = paymentQueueMaintenanceService.paymentQueueDepth();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("purgedReadyMessages", purged);
        out.put("remainingReadyMessages", depth);
        out.put("time", java.time.Instant.now().toString());
        return ResponseEntity.ok(out);
    }

    /**
     * Sum of all {@link Counter} meters with this base name (including tagged variants).
     * Using only {@code meterRegistry.find(name).counter()} can return a single time series and miss
     * others with the same name, which makes totals look inconsistent across polls.
     */
    private double counter(String name) {
        double sum = 0.0;
        for (Counter c : meterRegistry.find(name).counters()) {
            sum += c.count();
        }
        return sum;
    }

    /**
     * When {@link ClusterBusinessMetricsBridge} is enabled, prefer Redis totals (all instances).
     * Otherwise sum local Micrometer counters.
     */
    private double clusterOrLocal(String clusterSuffix, String micrometerName) {
        long v = clusterCounters.readLong(clusterSuffix);
        if (v == Long.MIN_VALUE || v == Long.MAX_VALUE) {
            return counter(micrometerName);
        }
        return (double) v;
    }

    private double gauge(String name) {
        io.micrometer.core.instrument.Gauge g = meterRegistry.find(name).gauge();
        return g == null ? 0.0 : g.value();
    }
}

