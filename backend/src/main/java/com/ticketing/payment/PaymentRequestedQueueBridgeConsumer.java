package com.ticketing.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.messaging.KafkaTopics;
import com.ticketing.messaging.RabbitConfig;
import com.ticketing.messaging.dto.PaymentRequestedEvent;
import com.ticketing.metrics.BusinessMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentRequestedQueueBridgeConsumer {

    private final ObjectMapper objectMapper;
    private final RabbitTemplate rabbitTemplate;
    private final BusinessMetrics businessMetrics;

    @KafkaListener(topics = KafkaTopics.PAYMENT_REQUESTED, groupId = "${spring.kafka.consumer.group-id}-payment-bridge")
    public void onPaymentRequested(String payload) {
        try {
            PaymentRequestedEvent event = objectMapper.readValue(payload, PaymentRequestedEvent.class);
            businessMetrics.incKafkaConsumed(KafkaTopics.PAYMENT_REQUESTED);
            rabbitTemplate.convertAndSend("", RabbitConfig.PAYMENT_QUEUE, event);
            businessMetrics.incRabbitPublished(RabbitConfig.PAYMENT_QUEUE);
            // "requested" should reflect what actually gets issued to the worker queue.
            businessMetrics.incPaymentRequested();
            log.info("Payment request enqueued reservationId={}", event.reservationId());
        } catch (Exception e) {
            log.error("Failed to enqueue payment request: {}", e.getMessage(), e);
        }
    }
}
