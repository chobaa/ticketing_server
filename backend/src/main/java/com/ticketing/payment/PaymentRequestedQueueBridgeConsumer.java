package com.ticketing.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.messaging.KafkaTopics;
import com.ticketing.messaging.RabbitConfig;
import com.ticketing.messaging.dto.PaymentRequestedEvent;
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

    @KafkaListener(topics = KafkaTopics.PAYMENT_REQUESTED, groupId = "${spring.kafka.consumer.group-id}-payment-bridge")
    public void onPaymentRequested(String payload) {
        try {
            PaymentRequestedEvent event = objectMapper.readValue(payload, PaymentRequestedEvent.class);
            rabbitTemplate.convertAndSend("", RabbitConfig.PAYMENT_QUEUE, event);
            log.info("Payment request enqueued reservationId={}", event.reservationId());
        } catch (Exception e) {
            log.error("Failed to enqueue payment request: {}", e.getMessage(), e);
        }
    }
}
