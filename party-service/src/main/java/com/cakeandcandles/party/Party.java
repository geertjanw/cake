package com.cakeandcandles.party;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record Party(
        String id,
        String name,
        LocalDate birthDate,
        int age,
        String flavor,
        List<String> guests,
        Status status,
        String cakeId,
        int invitationsSent,
        int invitationsFailed,
        String failureReason,
        Instant plannedAt
) {
    public enum Status { PLANNED, PARTIAL, FAILED }
}
