package com.ticketing.messaging.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record TicketReservedEvent(
        Long reservationId,
        Long userId,
        Long eventId,
        Long seatId,
        String seatNumber,
        BigDecimal price,
        Instant occurredAt) {}
