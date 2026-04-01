package com.ticketing.messaging.dto;

import java.time.Instant;

public record TicketCanceledEvent(
        Long reservationId, Long userId, Long eventId, Long seatId, String reason, Instant occurredAt) {}
