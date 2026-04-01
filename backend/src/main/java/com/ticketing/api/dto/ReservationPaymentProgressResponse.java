package com.ticketing.api.dto;

import java.time.Instant;

public record ReservationPaymentProgressResponse(
        Long reservationId,
        String reservationStatus,
        Instant reservedAt,
        String paymentStatus,
        Instant paymentStartedAt,
        Instant paymentFinishedAt,
        String failureCode,
        String failureMessage) {}
