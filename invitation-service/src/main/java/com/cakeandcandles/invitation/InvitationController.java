package com.cakeandcandles.invitation;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InvitationController {

    private final Mailroom mailroom;

    public InvitationController(Mailroom mailroom) {
        this.mailroom = mailroom;
    }

    @PostMapping("/invitations")
    public InvitationResponse send(@Valid @RequestBody InvitationRequest request) {
        return mailroom.sendAll(request);
    }
}
