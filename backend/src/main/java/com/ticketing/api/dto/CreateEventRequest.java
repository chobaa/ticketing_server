package com.ticketing.api.dto;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;

public record CreateEventRequest(
        @NotBlank @Size(max = 500) String name,
        @Size(max = 500) String venue,
        @NotBlank String startDate,
        @NotNull @Min(1) @Max(5000) Integer seatCount,
        @NotNull @DecimalMin("0.0") BigDecimal seatPrice,
        @Size(max = 32) String grade) {}
