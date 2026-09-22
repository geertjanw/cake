package com.cakeandcandles.party;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Past;

import java.time.LocalDate;
import java.util.List;

public record PartyRequest(
        @NotBlank String name,
        @Past LocalDate birthDate,
        @NotBlank String flavor,
        @NotEmpty List<String> guests
) {}
