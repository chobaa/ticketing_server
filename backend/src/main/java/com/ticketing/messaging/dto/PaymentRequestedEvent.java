package com.ticketing.messaging.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record PaymentRequestedEvent(
        Long reservationId,
        Long userId,
        Long eventId,
        Long seatId,
        BigDecimal amount,
        Instant occurredAt) {}
