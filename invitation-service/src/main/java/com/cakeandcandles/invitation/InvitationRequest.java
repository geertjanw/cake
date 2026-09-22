package com.cakeandcandles.invitation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record InvitationRequest(
        @NotBlank String partyId,
        @NotBlank String hostName,
        int age,
        @NotEmpty List<String> guests
) {}
