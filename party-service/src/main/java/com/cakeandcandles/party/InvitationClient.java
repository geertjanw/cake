package com.cakeandcandles.party;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.semconv.ErrorAttributes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;

@Component
public class InvitationClient {

    private static final Logger log = LoggerFactory.getLogger(InvitationClient.class);

    private static final AttributeKey<String> DISPATCH = AttributeKey.stringKey("invitations.dispatch");
    private static final AttributeKey<Long> GUEST_COUNT = AttributeKey.longKey("party.guest_count");

    /**
     * How the invitation round went, as opposed to how many letters landed.
     *
     * <p>The distinction is the point: {@link #PARTIAL} means invitation-service did its job
     * and some addresses bounced, while {@link #UNAVAILABLE} means we never reached it at
     * all. Both leave guests uninvited, so a bare "failed" count cannot tell them apart -
     * and one is a typo in a guest list, the other is an outage.
     */
    public enum Dispatch {
        /** Every invitation was accepted. */
        DELIVERED,
        /** invitation-service answered; some addresses bounced. */
        PARTIAL,
        /** invitation-service could not be reached, or failed outright. Nobody was invited. */
        UNAVAILABLE
    }

    public record Result(Dispatch dispatch, int sent, int failed, List<String> failedGuests) {}

    /** What invitation-service actually puts on the wire. */
    private record InvitationsResponse(int sent, int failed, List<String> failedGuests) {}

    private final RestClient http;

    public InvitationClient(RestClient.Builder builder, @Value("${clients.invitation.url}") String baseUrl) {
        this.http = builder.baseUrl(baseUrl).build();
    }

    public Result send(String partyId, String hostName, int age, List<String> guests) {
        try {
            InvitationsResponse response = http.post().uri("/invitations")
                    .body(Map.of("partyId", partyId, "hostName", hostName, "age", age, "guests", guests))
                    .retrieve().body(InvitationsResponse.class);

            if (response == null) {
                // A 2xx with no body. Rare, but returning "0 sent, 0 failed" here would
                // quietly report a party as fully invited when nothing is known.
                return unavailable(guests, "empty_response", null);
            }

            Dispatch dispatch = response.failed() == 0 ? Dispatch.DELIVERED : Dispatch.PARTIAL;
            Span.current().setAttribute(DISPATCH, dispatch.name());
            return new Result(dispatch, response.sent(), response.failed(), response.failedGuests());
        } catch (RestClientException e) {
            // Invitations are best-effort, so the party still happens - but "best-effort"
            // must not mean "silent". Without this the only trace of an invitation-service
            // outage is the agent's failed HTTP client span, while the business outcome
            // looks identical to a couple of mistyped addresses.
            return unavailable(guests, errorTypeOf(e), e);
        }
    }

    private Result unavailable(List<String> guests, String errorType, RestClientException cause) {
        Span span = Span.current();
        span.setAttribute(DISPATCH, Dispatch.UNAVAILABLE.name());
        span.setAttribute(ErrorAttributes.ERROR_TYPE, errorType);
        if (cause != null) {
            span.recordException(cause);
        }
        // An event rather than a span status: the party itself did not fail, so marking the
        // server span ERROR would inflate the endpoint's error rate for a degraded success.
        span.addEvent("invitations unavailable", Attributes.of(
                ErrorAttributes.ERROR_TYPE, errorType,
                GUEST_COUNT, (long) guests.size()));

        log.error("invitation-service unavailable ({}), {} guest(s) not invited",
                errorType, guests.size(), cause);
        return new Result(Dispatch.UNAVAILABLE, 0, guests.size(), guests);
    }

    /** Low-cardinality: the exception's simple name, never its message. */
    private static String errorTypeOf(RestClientException e) {
        return e.getClass().getSimpleName();
    }
}
