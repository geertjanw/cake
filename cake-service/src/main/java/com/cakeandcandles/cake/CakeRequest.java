package com.cakeandcandles.cake;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record CakeRequest(
        @NotBlank String partyId,
        @NotBlank String flavor,
        @Min(0) @Max(150) int candles
) {}
