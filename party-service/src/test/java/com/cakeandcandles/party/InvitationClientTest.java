package com.cakeandcandles.party;

import com.cakeandcandles.party.InvitationClient.Dispatch;
import com.cakeandcandles.party.InvitationClient.ErrorType;
import com.cakeandcandles.party.InvitationClient.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The point of these tests is the difference between "some guests bounced" and "we never
 * reached invitation-service". Both leave guests uninvited and both used to produce an
 * identical PARTIAL party, which made an outage look like a handful of typos.
 */
class InvitationClientTest {

    private static final List<String> GUESTS = List.of("a@example.com", "b@example.com");

    private RestClient.Builder builder;
    private MockRestServiceServer server;
    private InvitationClient client;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new InvitationClient(builder, "http://invitation-service");
    }

    @Test
    @DisplayName("all invitations accepted is DELIVERED")
    void allAcceptedIsDelivered() {
        server.expect(requestTo("http://invitation-service/invitations"))
                .andRespond(withSuccess("{\"sent\":2,\"failed\":0,\"failedGuests\":[]}",
                        MediaType.APPLICATION_JSON));

        Result result = client.send("party-1", "Ada", 36, GUESTS);

        assertThat(result.dispatch()).isEqualTo(Dispatch.DELIVERED);
        assertThat(result.errorType()).isNull();
        assertThat(result.sent()).isEqualTo(2);
        assertThat(result.failed()).isZero();
        server.verify();
    }

    @Test
    @DisplayName("bounced addresses are PARTIAL, not an outage")
    void bouncedAddressesArePartial() {
        server.expect(requestTo("http://invitation-service/invitations"))
                .andRespond(withSuccess(
                        "{\"sent\":1,\"failed\":1,\"failedGuests\":[\"b@example.com\"]}",
                        MediaType.APPLICATION_JSON));

        Result result = client.send("party-1", "Ada", 36, GUESTS);

        assertThat(result.dispatch()).isEqualTo(Dispatch.PARTIAL);
        // Bounced addresses are not an error.type: invitation-service worked perfectly.
        assertThat(result.errorType()).isNull();
        assertThat(result.sent()).isEqualTo(1);
        assertThat(result.failedGuests()).containsExactly("b@example.com");
    }

    @Test
    @DisplayName("a 5xx from invitation-service is UNAVAILABLE, with nobody invited")
    void serverErrorIsUnavailable() {
        server.expect(requestTo("http://invitation-service/invitations"))
                .andRespond(withServerError());

        Result result = client.send("party-1", "Ada", 36, GUESTS);

        assertThat(result.dispatch()).isEqualTo(Dispatch.UNAVAILABLE);
        assertThat(result.errorType()).isEqualTo(ErrorType.SERVER_ERROR);
        assertThat(result.sent()).isZero();
        assertThat(result.failed()).isEqualTo(GUESTS.size());
        assertThat(result.failedGuests()).containsExactlyElementsOf(GUESTS);
    }

    @Test
    @DisplayName("an unreachable invitation-service is UNAVAILABLE, not an exception")
    void connectionFailureIsUnavailable() {
        // Invitations are best-effort: the party must still be planned. What must not
        // happen is the failure vanishing without a trace.
        server.expect(requestTo("http://invitation-service/invitations"))
                .andRespond(withException(new java.net.ConnectException("connection refused")));

        Result result = client.send("party-1", "Ada", 36, GUESTS);

        assertThat(result.dispatch()).isEqualTo(Dispatch.UNAVAILABLE);
        assertThat(result.errorType()).isEqualTo(ErrorType.UNREACHABLE);
        assertThat(result.failedGuests()).containsExactlyElementsOf(GUESTS);
    }

    @Test
    @DisplayName("a 4xx is a rejected request, not an unreachable service")
    void clientErrorIsRejected() {
        // Same Dispatch, different cause: nothing about invitation-service is broken, so
        // this must not land in the same bucket as an outage.
        server.expect(requestTo("http://invitation-service/invitations"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));

        Result result = client.send("party-1", "Ada", 36, GUESTS);

        assertThat(result.dispatch()).isEqualTo(Dispatch.UNAVAILABLE);
        assertThat(result.errorType()).isEqualTo(ErrorType.REJECTED);
    }

    @Test
    @DisplayName("a 2xx with no body is UNAVAILABLE rather than a silent success")
    void emptyBodyIsUnavailable() {
        // The trap: treating a null body as "0 sent, 0 failed" would report DELIVERED and
        // mark the party PLANNED, claiming every guest was invited on no evidence at all.
        server.expect(requestTo("http://invitation-service/invitations"))
                .andRespond(withSuccess().contentType(MediaType.APPLICATION_JSON));

        Result result = client.send("party-1", "Ada", 36, GUESTS);

        assertThat(result.dispatch()).isEqualTo(Dispatch.UNAVAILABLE);
        assertThat(result.errorType()).isEqualTo(ErrorType.EMPTY_RESPONSE);
        assertThat(result.failed()).isEqualTo(GUESTS.size());
    }

    @Test
    @DisplayName("UNAVAILABLE reports every guest as uninvited")
    void unavailableReportsAllGuestsFailed() {
        // sent + failed must still equal the guest list, or downstream counts drift.
        server.expect(requestTo("http://invitation-service/invitations"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        Result result = client.send("party-1", "Ada", 36, GUESTS);

        assertThat(result.sent() + result.failed()).isEqualTo(GUESTS.size());
    }

    @Test
    @DisplayName("error.type values are the ones we chose, not exception class names")
    void errorTypeValuesAreBounded() {
        // The regression this guards: deriving error.type from the exception class meant
        // every RestClientException subclass Spring adds became a new metric time series.
        assertThat(ErrorType.values())
                .extracting(ErrorType::value)
                .containsExactlyInAnyOrder("invitation_service_error", "invitations_rejected",
                        "invitation_service_unreachable", "empty_response", "_OTHER");
    }
}
