package com.ticketing.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.messaging.dto.TicketReservedEvent;
import com.ticketing.metrics.BusinessMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class TicketReservedConsumer {

    private final ObjectMapper objectMapper;
    private final RabbitTemplate rabbitTemplate;
    private final BusinessMetrics businessMetrics;

    @KafkaListener(topics = KafkaTopics.TICKET_RESERVED, groupId = "${spring.kafka.consumer.group-id}")
    public void onTicketReserved(String payload) {
        try {
            TicketReservedEvent event = objectMapper.readValue(payload, TicketReservedEvent.class);
            businessMetrics.incKafkaConsumed(KafkaTopics.TICKET_RESERVED);
            rabbitTemplate.convertAndSend(
                    "",
                    RabbitConfig.NOTIFICATION_QUEUE,
                    Map.of(
                            "type",
                            "TICKET_RESERVED",
                            "reservationId",
                            event.reservationId(),
                            "userId",
                            event.userId()));
            businessMetrics.incRabbitPublished(RabbitConfig.NOTIFICATION_QUEUE);
            log.debug("Routed reservation {} to notification queue", event.reservationId());
        } catch (Exception e) {
            log.error("Failed to process ticket-reserved: {}", e.getMessage(), e);
        }
    }
}
