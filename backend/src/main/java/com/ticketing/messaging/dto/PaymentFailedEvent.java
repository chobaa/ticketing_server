package com.ticketing.messaging.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record PaymentFailedEvent(
        Long reservationId,
        Long userId,
        Long eventId,
        Long seatId,
        String orderId,
        BigDecimal amount,
        String failureCode,
        String failureMessage,
        Instant occurredAt) {}
