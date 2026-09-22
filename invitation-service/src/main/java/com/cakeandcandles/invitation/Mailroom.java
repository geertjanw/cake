package com.cakeandcandles.invitation;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * Sends one invitation per guest. Each guest gets its own child span, so a single
 * request fans out into N spans - and one bad e-mail address fails one span
 * without failing the whole request (a "partial failure" inside a trace).
 */
@Service
public class Mailroom {

    private static final Logger log = LoggerFactory.getLogger(Mailroom.class);
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private static final AttributeKey<String> STATUS = AttributeKey.stringKey("invitation.status");
    private static final AttributeKey<String> PARTY_ID = AttributeKey.stringKey("party.id");

    private final Tracer tracer = GlobalOpenTelemetry.getTracer("invitation-service");
    private final LongCounter invitations;

    public Mailroom() {
        Meter meter = GlobalOpenTelemetry.getMeter("invitation-service");
        this.invitations = meter.counterBuilder("invitations.sent")
                .setDescription("Invitations attempted, by status").setUnit("{invitation}").build();
    }

    public InvitationResponse sendAll(InvitationRequest req) {
        Span.current().setAttribute("invitations.count", req.guests().size());
        Span.current().setAttribute(PARTY_ID, req.partyId());

        List<String> failed = new ArrayList<>();
        for (String guest : req.guests()) {
            if (!sendOne(req, guest)) {
                failed.add(guest);
            }
        }
        int sent = req.guests().size() - failed.size();
        log.info("Party {}: sent {} invitations, {} failed", req.partyId(), sent, failed.size());
        return new InvitationResponse(sent, failed.size(), failed);
    }

    private boolean sendOne(InvitationRequest req, String guest) {
        Span span = tracer.spanBuilder("send invitation")
                .setAttribute(PARTY_ID, req.partyId())
                // e-mail is PII - keep only the domain on the span
                .setAttribute("invitation.recipient.domain", domainOf(guest))
                .startSpan();
        try (Scope ignored = span.makeCurrent()) {
            if (!EMAIL.matcher(guest).matches()) {
                throw new IllegalArgumentException("Invalid e-mail address");
            }
            // Pretend to talk to an SMTP server.
            Thread.sleep(ThreadLocalRandom.current().nextLong(20, 120));
            log.debug("Invitation for {}'s {}th birthday sent to {}", req.hostName(), req.age(), domainOf(guest));
            invitations.add(1, Attributes.of(STATUS, "sent"));
            span.setAttribute(STATUS, "sent");
            return true;
        } catch (IllegalArgumentException e) {
            log.warn("Could not invite guest for party {}: {}", req.partyId(), e.getMessage());
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.setAttribute(STATUS, "failed");
            invitations.add(1, Attributes.of(STATUS, "failed"));
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } finally {
            span.end();
        }
    }

    private static String domainOf(String email) {
        int at = email.indexOf('@');
        return at < 0 ? "invalid" : email.substring(at + 1);
    }
}
