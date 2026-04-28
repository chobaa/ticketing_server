package com.ticketing.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.messaging.KafkaTopics;
import com.ticketing.messaging.dto.PaymentFailedEvent;
import com.ticketing.messaging.dto.PaymentSucceededEvent;
import com.ticketing.metrics.BusinessMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentResultConsumer {

    private final ObjectMapper objectMapper;
    private final ReservationSettlementService reservationSettlementService;
    private final BusinessMetrics businessMetrics;

    @KafkaListener(topics = KafkaTopics.PAYMENT_SUCCEEDED, groupId = "${spring.kafka.consumer.group-id}")
    public void onPaymentSucceeded(String payload) {
        try {
            PaymentSucceededEvent event = objectMapper.readValue(payload, PaymentSucceededEvent.class);
            businessMetrics.incKafkaConsumed(KafkaTopics.PAYMENT_SUCCEEDED);
            reservationSettlementService.settleSuccess(event.reservationId());
            log.info("Payment settled success reservationId={}", event.reservationId());
        } catch (Exception e) {
            log.error("Failed to settle payment success event: {}", e.getMessage(), e);
        }
    }

    @KafkaListener(topics = KafkaTopics.PAYMENT_FAILED, groupId = "${spring.kafka.consumer.group-id}")
    public void onPaymentFailed(String payload) {
        try {
            PaymentFailedEvent event = objectMapper.readValue(payload, PaymentFailedEvent.class);
            businessMetrics.incKafkaConsumed(KafkaTopics.PAYMENT_FAILED);
            String reason = event.failureCode() + ":" + event.failureMessage();
            reservationSettlementService.settleFailure(event.reservationId(), reason, true);
            log.info("Payment settled failure reservationId={}", event.reservationId());
        } catch (Exception e) {
            log.error("Failed to settle payment failed event: {}", e.getMessage(), e);
        }
    }
}
