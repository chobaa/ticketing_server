package com.ticketing.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.messaging.dto.QueueEnterEvent;
import com.ticketing.messaging.dto.TicketReservedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ReservationEventProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public void publishTicketReserved(TicketReservedEvent event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            kafkaTemplate.send(KafkaTopics.TICKET_RESERVED, String.valueOf(event.reservationId()), json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public void publishQueueEnter(QueueEnterEvent event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            kafkaTemplate.send(KafkaTopics.QUEUE_ENTER, String.valueOf(event.eventId()), json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
