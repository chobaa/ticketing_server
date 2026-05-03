package com.ticketing.api.dto;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;

public record CreateEventRequest(
        @NotBlank @Size(max = 500) String name,
        @Size(max = 500) String venue,
        @NotBlank String startDate,
        @NotNull @Min(1) @Max(5000) Integer seatCount,
        @NotNull @DecimalMin("0.0") BigDecimal seatPrice,
        @Size(max = 32) String grade,
        /** {@code PUBLIC} or {@code LOAD_TEST}; omit or null for {@code PUBLIC}. */
        @Size(max = 32) String listingScope) {}
