package com.ticketing.messaging.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record PaymentSucceededEvent(
        Long reservationId,
        Long userId,
        Long eventId,
        Long seatId,
        String orderId,
        String paymentKey,
        BigDecimal amount,
        String method,
        Instant occurredAt) {}
