package com.ticketing.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class BusinessMetrics {

    private final MeterRegistry registry;

    private final Counter paymentSucceeded;
    private final Counter paymentFailed;

    private final Map<String, Counter> kafkaProduced = new ConcurrentHashMap<>();
    private final Map<String, Counter> kafkaConsumed = new ConcurrentHashMap<>();
    private final Map<String, Counter> rabbitPublished = new ConcurrentHashMap<>();
    private final Map<String, Counter> rabbitConsumed = new ConcurrentHashMap<>();
    private final Map<String, Counter> redisOps = new ConcurrentHashMap<>();

    public BusinessMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.paymentSucceeded =
                Counter.builder("ticketing.payment.succeeded.total")
                        .description("Total count of payment success settlements")
                        .register(registry);
        this.paymentFailed =
                Counter.builder("ticketing.payment.failed.total")
                        .description("Total count of payment failure settlements")
                        .register(registry);
    }

    public void incPaymentSucceeded() {
        paymentSucceeded.increment();
    }

    public void incPaymentFailed() {
        paymentFailed.increment();
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

