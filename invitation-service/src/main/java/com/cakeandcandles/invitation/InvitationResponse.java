package com.cakeandcandles.invitation;

import java.util.List;

public record InvitationResponse(int sent, int failed, List<String> failedGuests) {}
