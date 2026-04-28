package com.ticketing.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class BusinessMetrics {

    private final MeterRegistry registry;

    private final Counter paymentRequested;
    private final Counter paymentSucceeded;
    private final Counter paymentFailed;
    private final Counter paymentRequestDroppedMissingReservation;
    private final Counter paymentRequestSkippedDuplicate;
    private final Counter paymentSettleSkippedAlreadyTerminal;
    private final Counter paymentWorkerSleepMsTotal;
    private final java.util.concurrent.atomic.AtomicInteger paymentWorkersSleeping;
    private final java.util.function.Supplier<Double> paymentQueueDepthSupplier;

    private final Map<String, Counter> kafkaProduced = new ConcurrentHashMap<>();
    private final Map<String, Counter> kafkaConsumed = new ConcurrentHashMap<>();
    private final Map<String, Counter> rabbitPublished = new ConcurrentHashMap<>();
    private final Map<String, Counter> rabbitConsumed = new ConcurrentHashMap<>();
    private final Map<String, Counter> redisOps = new ConcurrentHashMap<>();

    public BusinessMetrics(MeterRegistry registry) {
        this.registry = registry;
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
        this.paymentQueueDepthSupplier = () -> 0.0;

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
    }

    public void incPaymentRequested() {
        paymentRequested.increment();
    }

    public void incPaymentSucceeded() {
        paymentSucceeded.increment();
    }

    public void incPaymentFailed() {
        paymentFailed.increment();
    }

    public void incPaymentRequestDroppedMissingReservation() {
        paymentRequestDroppedMissingReservation.increment();
    }

    public void incPaymentRequestSkippedDuplicate() {
        paymentRequestSkippedDuplicate.increment();
    }

    public void incPaymentSettleSkippedAlreadyTerminal() {
        paymentSettleSkippedAlreadyTerminal.increment();
    }

    public void addPaymentWorkerSleepMs(long ms) {
        if (ms <= 0) return;
        paymentWorkerSleepMsTotal.increment(ms);
    }

    public void incPaymentWorkersSleeping() {
        paymentWorkersSleeping.incrementAndGet();
    }

    public void decPaymentWorkersSleeping() {
        paymentWorkersSleeping.decrementAndGet();
    }

    public void incKafkaProduced(String topic) {
        counter(kafkaProduced, "ticketing.messaging.kafka.produced.total", "topic", safe(topic))
                .increment();
    }

    public void incKafkaConsumed(String topic) {
        counter(kafkaConsumed, "ticketing.messaging.kafka.consumed.total", "topic", safe(topic))
                .increment();
    }

    public void incRabbitPublished(String queue) {
        counter(rabbitPublished, "ticketing.messaging.rabbit.published.total", "queue", safe(queue))
                .increment();
    }

    public void incRabbitConsumed(String queue) {
        counter(rabbitConsumed, "ticketing.messaging.rabbit.consumed.total", "queue", safe(queue))
                .increment();
    }

    public void incRedisOp(String op) {
        counter(redisOps, "ticketing.redis.ops.total", "op", safe(op))
                .increment();
    }

    private Counter counter(Map<String, Counter> cache, String name, String tagKey, String tagValue) {
        String key = name + "|" + tagKey + "=" + tagValue;
        return cache.computeIfAbsent(
                key,
                ignored ->
                        Counter.builder(name)
                                .description("Business-level message/operation counters")
                                .tag(tagKey, tagValue)
                                .register(registry));
    }

    private static String safe(String s) {
        if (s == null) return "unknown";
        String t = s.trim();
        return t.isEmpty() ? "unknown" : t;
    }
}

