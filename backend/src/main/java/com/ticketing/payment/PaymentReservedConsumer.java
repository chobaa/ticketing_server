package com.ticketing.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.messaging.KafkaTopics;
import com.ticketing.messaging.ReservationEventProducer;
import com.ticketing.messaging.dto.PaymentRequestedEvent;
import com.ticketing.messaging.dto.TicketReservedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentReservedConsumer {

    private final ObjectMapper objectMapper;
    private final ReservationEventProducer reservationEventProducer;

    @KafkaListener(topics = KafkaTopics.TICKET_RESERVED, groupId = "${spring.kafka.consumer.group-id}-payment")
    public void onTicketReserved(String payload) {
        try {
            TicketReservedEvent event = objectMapper.readValue(payload, TicketReservedEvent.class);
            reservationEventProducer.publishPaymentRequested(new PaymentRequestedEvent(
                    event.reservationId(),
                    event.userId(),
                    event.eventId(),
                    event.seatId(),
                    event.price(),
                    Instant.now()));
            log.info("Payment requested reservationId={} amount={}", event.reservationId(), event.price());
        } catch (Exception e) {
            log.error("Failed to process payment request from reserved event: {}", e.getMessage(), e);
        }
    }
}
