package com.ticketing.metrics;

import com.ticketing.messaging.RabbitConfig;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.stereotype.Component;

import java.util.Properties;

@Slf4j
@Component
@RequiredArgsConstructor
public class RabbitQueueDepthMetrics {

    private final AmqpAdmin amqpAdmin;
    private final MeterRegistry registry;

    @jakarta.annotation.PostConstruct
    public void register() {
        io.micrometer.core.instrument.Gauge.builder(
                        "ticketing.payment.queue.depth",
                        registry,
                        r -> queueDepthSafe(RabbitConfig.PAYMENT_QUEUE))
                .description("RabbitMQ queue depth for payment worker queue")
                .register(registry);
    }

    private double queueDepthSafe(String queueName) {
        try {
            if (amqpAdmin == null) return 0.0;
            Properties props = amqpAdmin.getQueueProperties(queueName);
            if (props == null) return 0.0;
            Object mc = props.get("QUEUE_MESSAGE_COUNT");
            if (mc instanceof Number n) return n.doubleValue();
            if (mc != null) return Double.parseDouble(String.valueOf(mc));
            return 0.0;
        } catch (Exception e) {
            log.debug("Failed to read rabbit queue depth for {}: {}", queueName, e.getMessage());
            return 0.0;
        }
    }
}

