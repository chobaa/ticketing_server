package com.ticketing.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.messaging.dto.PaymentFailedEvent;
import com.ticketing.messaging.dto.PaymentRequestedEvent;
import com.ticketing.messaging.dto.PaymentSucceededEvent;
import com.ticketing.messaging.dto.QueueEnterEvent;
import com.ticketing.messaging.dto.TicketCanceledEvent;
import com.ticketing.messaging.dto.TicketReservedEvent;
import com.ticketing.metrics.BusinessMetrics;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ReservationEventProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final BusinessMetrics businessMetrics;

    public void publishTicketReserved(TicketReservedEvent event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            kafkaTemplate.send(KafkaTopics.TICKET_RESERVED, String.valueOf(event.reservationId()), json);
            businessMetrics.incKafkaProduced(KafkaTopics.TICKET_RESERVED);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public void publishQueueEnter(QueueEnterEvent event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            kafkaTemplate.send(KafkaTopics.QUEUE_ENTER, String.valueOf(event.eventId()), json);
            businessMetrics.incKafkaProduced(KafkaTopics.QUEUE_ENTER);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public void publishPaymentRequested(PaymentRequestedEvent event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            kafkaTemplate.send(KafkaTopics.PAYMENT_REQUESTED, String.valueOf(event.reservationId()), json);
            businessMetrics.incKafkaProduced(KafkaTopics.PAYMENT_REQUESTED);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public void publishPaymentSucceeded(PaymentSucceededEvent event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            kafkaTemplate.send(KafkaTopics.PAYMENT_SUCCEEDED, String.valueOf(event.reservationId()), json);
            businessMetrics.incKafkaProduced(KafkaTopics.PAYMENT_SUCCEEDED);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public void publishPaymentFailed(PaymentFailedEvent event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            kafkaTemplate.send(KafkaTopics.PAYMENT_FAILED, String.valueOf(event.reservationId()), json);
            businessMetrics.incKafkaProduced(KafkaTopics.PAYMENT_FAILED);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public void publishTicketCanceled(TicketCanceledEvent event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            kafkaTemplate.send(KafkaTopics.TICKET_CANCELED, String.valueOf(event.reservationId()), json);
            businessMetrics.incKafkaProduced(KafkaTopics.TICKET_CANCELED);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
