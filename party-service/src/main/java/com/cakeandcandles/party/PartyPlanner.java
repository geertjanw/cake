package com.cakeandcandles.party;

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

import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Orchestrates a party: work out the age, order the cake, send the invitations.
 * One POST /parties call becomes one distributed trace across all three services.
 */
@Service
public class PartyPlanner {

    private static final Logger log = LoggerFactory.getLogger(PartyPlanner.class);

    private static final AttributeKey<String> OUTCOME = AttributeKey.stringKey("party.outcome");
    private static final AttributeKey<String> FLAVOR = AttributeKey.stringKey("cake.flavor");

    private final CakeClient cakes;
    private final InvitationClient invitations;
    private final Map<String, Party> parties = new ConcurrentHashMap<>();

    private final Tracer tracer = GlobalOpenTelemetry.getTracer("party-service");
    private final LongCounter partiesPlanned;

    public PartyPlanner(CakeClient cakes, InvitationClient invitations) {
        this.cakes = cakes;
        this.invitations = invitations;
        Meter meter = GlobalOpenTelemetry.getMeter("party-service");
        this.partiesPlanned = meter.counterBuilder("parties.planned")
                .setDescription("Parties planned, by outcome").setUnit("{party}").build();
        meter.gaugeBuilder("parties.stored").ofLongs()
                .setDescription("Parties currently held in memory")
                .buildWithCallback(m -> m.record(parties.size()));
    }

    public Party plan(PartyRequest req) {
        String partyId = UUID.randomUUID().toString();
        int age = Period.between(req.birthDate(), LocalDate.now()).getYears();

        // Enrich the agent's HTTP server span with business attributes. Age is a great
        // attribute to filter and group by in Dash0 - low cardinality, meaningful.
        Span current = Span.current();
        current.setAttribute("party.id", partyId);
        current.setAttribute("party.age", age);
        current.setAttribute(FLAVOR, req.flavor());
        current.setAttribute("party.guest_count", req.guests().size());

        log.info("Planning {}'s {}th birthday (party {}) with {} guests and a {} cake",
                req.name(), age, partyId, req.guests().size(), req.flavor());

        // Step 1: the cake. If this fails the party is off.
        CakeClient.Cake cake;
        try {
            cake = cakes.order(partyId, req.flavor(), age);
        } catch (CakeClient.CakeException e) {
            log.error("No cake for party {}: {}", partyId, e.getMessage());
            current.setStatus(StatusCode.ERROR, "cake order failed");
            current.recordException(e);
            partiesPlanned.add(1, Attributes.of(OUTCOME, "failed", FLAVOR, req.flavor()));
            return store(new Party(partyId, req.name(), req.birthDate(), age, req.flavor(), req.guests(),
                    Party.Status.FAILED, null, 0, 0, e.getMessage(), Instant.now()));
        }

        // Step 2: invitations. A few bounced e-mails shouldn't cancel the party.
        InvitationClient.Result inv = invitations.send(partyId, req.name(), age, req.guests());
        Party.Status status = inv.failed() == 0 ? Party.Status.PLANNED : Party.Status.PARTIAL;
        partiesPlanned.add(1, Attributes.of(OUTCOME, status.name().toLowerCase(), FLAVOR, req.flavor()));

        current.addEvent("party planned", Attributes.of(
                AttributeKey.stringKey("party.status"), status.name(),
                AttributeKey.longKey("invitations.failed"), (long) inv.failed()));

        return store(new Party(partyId, req.name(), req.birthDate(), age, req.flavor(), req.guests(),
                status, cake.cakeId(), inv.sent(), inv.failed(), null, Instant.now()));
    }

    /** Used by the scheduled job: a span that is NOT rooted in an HTTP request. */
    public int countUpcomingBirthdays(int withinDays) {
        Span span = tracer.spanBuilder("count upcoming birthdays")
                .setAttribute("lookahead.days", withinDays)
                .startSpan();
        try (Scope ignored = span.makeCurrent()) {
            LocalDate today = LocalDate.now();
            long count = parties.values().stream()
                    .filter(p -> {
                        LocalDate next = p.birthDate().withYear(today.getYear());
                        if (next.isBefore(today)) next = next.plusYears(1);
                        return !next.isAfter(today.plusDays(withinDays));
                    })
                    .count();
            span.setAttribute("birthdays.upcoming", count);
            return (int) count;
        } finally {
            span.end();
        }
    }

    public Collection<Party> all() { return parties.values(); }

    public Optional<Party> find(String id) { return Optional.ofNullable(parties.get(id)); }

    private Party store(Party p) {
        parties.put(p.id(), p);
        return p;
    }
}
