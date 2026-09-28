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
import io.opentelemetry.semconv.ErrorAttributes;
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
    private static final AttributeKey<String> DISPATCH = AttributeKey.stringKey("invitations.dispatch");

    private final CakeClient cakes;
    private final InvitationClient invitations;
    private final CakeFlavors flavors;
    private final Map<String, Party> parties = new ConcurrentHashMap<>();

    private final Tracer tracer = GlobalOpenTelemetry.getTracer("party-service");
    private final LongCounter partiesPlanned;
    private final LongCounter invitationDispatches;

    public PartyPlanner(CakeClient cakes, InvitationClient invitations, CakeFlavors flavors) {
        this.cakes = cakes;
        this.invitations = invitations;
        this.flavors = flavors;
        Meter meter = GlobalOpenTelemetry.getMeter("party-service");
        this.partiesPlanned = meter.counterBuilder("parties.planned")
                .setDescription("Parties planned, by outcome").setUnit("{party}").build();
        this.invitationDispatches = meter.counterBuilder("invitations.dispatch")
                .setDescription("Invitation rounds, by whether invitation-service handled them")
                .setUnit("{dispatch}").build();
        meter.gaugeBuilder("parties.stored").ofLongs()
                .setDescription("Parties currently held in memory")
                .buildWithCallback(m -> m.record(parties.size()));
    }

    public Party plan(PartyRequest req) {
        // Before anything is recorded: this is what keeps cake.flavor bounded on
        // parties.planned. A flavor nobody bakes is a bad request, not a failed party.
        flavors.require(req.flavor());

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
            log.error("No cake for party {}", partyId, e);
            current.setStatus(StatusCode.ERROR, "cake order failed");
            current.recordException(e);
            // Why it failed, not just that it did: out_of_stock, oven_timeout and
            // cake_rejected call for completely different responses, and only the first
            // is a bakery problem at all. It goes on the metric rather than on the server
            // span, because the agent owns error.type there: its HTTP instrumentation
            // overwrites the key at span end with the status code for any 5xx.
            partiesPlanned.add(1, Attributes.of(
                    OUTCOME, "failed",
                    FLAVOR, req.flavor(),
                    ErrorAttributes.ERROR_TYPE, e.errorType().value()));
            return store(new Party(partyId, req.name(), req.birthDate(), age, req.flavor(), req.guests(),
                    Party.Status.FAILED, null, 0, 0, e.getMessage(), Instant.now()));
        }

        // Step 2: invitations. A few bounced e-mails shouldn't cancel the party.
        InvitationClient.Result inv = invitations.send(partyId, req.name(), age, req.guests());
        Party.Status status = inv.failed() == 0 ? Party.Status.PLANNED : Party.Status.PARTIAL;

        // Counted separately from party.outcome, because the two answer different questions:
        // party.outcome is "did the customer get a party", invitations.dispatch is "is the
        // invitation path healthy". Rolled into one attribute, an invitation-service outage
        // is indistinguishable from guests mistyping their addresses - both are "partial".
        invitationDispatches.add(1, Attributes.of(DISPATCH, inv.dispatch().name().toLowerCase()));
        partiesPlanned.add(1, Attributes.of(OUTCOME, status.name().toLowerCase(), FLAVOR, req.flavor()));

        // Only set when nobody could be invited, so the API response says why the party is
        // PARTIAL rather than leaving the caller to guess.
        String failureReason = inv.dispatch() == InvitationClient.Dispatch.UNAVAILABLE
                ? "invitations could not be sent: invitation-service unavailable"
                : null;

        current.addEvent("party planned", Attributes.of(
                AttributeKey.stringKey("party.status"), status.name(),
                DISPATCH, inv.dispatch().name(),
                AttributeKey.longKey("invitations.failed"), (long) inv.failed()));

        return store(new Party(partyId, req.name(), req.birthDate(), age, req.flavor(), req.guests(),
                status, cake.cakeId(), inv.sent(), inv.failed(), failureReason, Instant.now()));
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
