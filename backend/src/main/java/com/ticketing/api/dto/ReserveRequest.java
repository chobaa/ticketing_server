package com.ticketing.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ReserveRequest(
        @NotNull Long seatId, @NotBlank String admissionToken) {}
