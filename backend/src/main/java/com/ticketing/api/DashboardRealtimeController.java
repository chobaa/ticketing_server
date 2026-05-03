package com.ticketing.api;

import com.ticketing.metrics.MetricsSnapshotService;
import com.ticketing.payment.PaymentRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
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

    /** HTTP fallback for real-time snapshot when WebSocket isn't available. */
    @GetMapping("/realtime")
    public ResponseEntity<Map<String, Object>> realtime() {
        return ResponseEntity.ok(metricsSnapshotService.buildSnapshot());
    }

    @GetMapping("/business-metrics")
    public ResponseEntity<Map<String, Object>> businessMetrics() {
        Map<String, Object> m = new LinkedHashMap<>();
        // requested := issued-to-worker-queue count (not "event created")
        double requested = counter("ticketing.payment.requested.total");
        double succeeded = counter("ticketing.payment.succeeded.total");
        double failed = counter("ticketing.payment.failed.total");
        double dropped = counter("ticketing.payment.request.dropped.missing_reservation.total");
        double duplicate = counter("ticketing.payment.request.skipped.duplicate.total");
        double settleAlreadyTerminal = counter("ticketing.payment.settle.skipped.already_terminal.total");

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
        double sleepMsTotal = counter("ticketing.payment.worker.sleep.ms.total");

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
        m.put("time", java.time.Instant.now().toString());
        return ResponseEntity.ok(m);
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

    private double counter(String name) {
        Counter c = meterRegistry.find(name).counter();
        return c == null ? 0.0 : c.count();
    }

    private double gauge(String name) {
        io.micrometer.core.instrument.Gauge g = meterRegistry.find(name).gauge();
        return g == null ? 0.0 : g.value();
    }
}

