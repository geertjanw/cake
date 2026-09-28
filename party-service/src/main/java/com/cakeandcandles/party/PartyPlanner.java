package com.cakeandcandles.party;

import com.cakeandcandles.semconv.CakeAttributes;
import com.cakeandcandles.semconv.CakeAttributes.CakePartyOutcomeValues;
import com.cakeandcandles.semconv.CakeMetrics;
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

    private static final AttributeKey<String> OUTCOME = CakeAttributes.CAKE_PARTY_OUTCOME;
    private static final AttributeKey<String> FLAVOR = CakeAttributes.CAKE_FLAVOR;
    private static final AttributeKey<String> DISPATCH = CakeAttributes.CAKE_INVITATIONS_DISPATCH;

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
        this.partiesPlanned = CakeMetrics.createCakePartiesPlanned(meter);
        this.invitationDispatches = CakeMetrics.createCakeInvitationsDispatches(meter);
        CakeMetrics.createCakePartiesStored(meter)
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
        current.setAttribute(CakeAttributes.CAKE_PARTY_ID, partyId);
        current.setAttribute(CakeAttributes.CAKE_PARTY_AGE, (long) age);
        current.setAttribute(FLAVOR, req.flavor());
        current.setAttribute(CakeAttributes.CAKE_PARTY_GUEST_COUNT, (long) req.guests().size());

        log.info("Planning {}'s {}th birthday (party {}) with {} guests and a {} cake",
                req.name(), age, partyId, req.guests().size(), req.flavor());

        // Step 1: the cake. If this fails the party is off.
        CakeClient.Cake cake;
        try {
            cake = cakes.order(partyId, req.flavor(), age);
        } catch (CakeClient.CakeException e) {
            if (e.errorType() == CakeClient.ErrorType.REJECTED) {
                // The bakery refused the order itself, so this is a bad request, not a
                // failed party - handled like an unknown flavor. Deliberately no span
                // ERROR, no parties.planned increment and no stored party: counting a
                // caller's mistake as a failed party would inflate the failure rate with
                // things no amount of fixing the bakery could prevent. It stays visible as
                // a 400 on the server span, and in the log record below, which carries the
                // reason the bakery gave.
                log.warn("Bakery rejected the order for party {}", partyId, e);
                throw new PartyExceptions.CakeRejectedException(e.getMessage(), e);
            }
            log.error("No cake for party {}", partyId, e);
            current.setStatus(StatusCode.ERROR, "cake order failed");
            current.recordException(e);
            // Why it failed, not just that it did: out_of_stock, oven_timeout and
            // cake_rejected call for completely different responses, and only the first
            // is a bakery problem at all. It goes on the metric rather than on the server
            // span, because the agent owns error.type there: its HTTP instrumentation
            // overwrites the key at span end with the status code for any 5xx.
            partiesPlanned.add(1, Attributes.of(
                    OUTCOME, CakePartyOutcomeValues.FAILED,
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
        // error.type only when there is an error to type, and only ever one of
        // InvitationClient.ErrorType - which is what makes putting it on a metric safe.
        String dispatch = inv.dispatch().value();
        invitationDispatches.add(1, inv.errorType() == null
                ? Attributes.of(DISPATCH, dispatch)
                : Attributes.of(DISPATCH, dispatch,
                        ErrorAttributes.ERROR_TYPE, inv.errorType().value()));
        partiesPlanned.add(1, Attributes.of(OUTCOME, outcomeOf(status), FLAVOR, req.flavor()));

        // Only set when nobody could be invited, so the API response says why the party is
        // PARTIAL rather than leaving the caller to guess.
        String failureReason = inv.dispatch() == InvitationClient.Dispatch.UNAVAILABLE
                ? "invitations could not be sent: invitation-service unavailable"
                : null;

        current.addEvent("party planned", Attributes.of(
                OUTCOME, outcomeOf(status),
                DISPATCH, inv.dispatch().value(),
                CakeAttributes.CAKE_INVITATIONS_FAILED, (long) inv.failed()));

        return store(new Party(partyId, req.name(), req.birthDate(), age, req.flavor(), req.guests(),
                status, cake.cakeId(), inv.sent(), inv.failed(), failureReason, Instant.now()));
    }

    /** Used by the scheduled job: a span that is NOT rooted in an HTTP request. */
    public int countUpcomingBirthdays(int withinDays) {
        Span span = tracer.spanBuilder("count upcoming birthdays")
                .setAttribute(CakeAttributes.CAKE_BIRTHDAYS_LOOKAHEAD_DAYS, (long) withinDays)
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
            span.setAttribute(CakeAttributes.CAKE_BIRTHDAYS_UPCOMING, count);
            return (int) count;
        } finally {
            span.end();
        }
    }

    public Collection<Party> all() { return parties.values(); }

    public Optional<Party> find(String id) { return Optional.ofNullable(parties.get(id)); }

    /**
     * The registry decides what cake.party.outcome may say, so the mapping is explicit
     * rather than status.name().toLowerCase(): a new Party.Status would then be a compile
     * error here instead of a new time series on cake.parties.planned.
     */
    private static String outcomeOf(Party.Status status) {
        return switch (status) {
            case PLANNED -> CakePartyOutcomeValues.PLANNED;
            case PARTIAL -> CakePartyOutcomeValues.PARTIAL;
            case FAILED -> CakePartyOutcomeValues.FAILED;
        };
    }

    private Party store(Party p) {
        parties.put(p.id(), p);
        return p;
    }
}
