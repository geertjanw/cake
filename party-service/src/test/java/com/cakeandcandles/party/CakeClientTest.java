package com.cakeandcandles.party;

import com.cakeandcandles.party.CakeClient.Cake;
import com.cakeandcandles.party.CakeClient.CakeException;
import com.cakeandcandles.party.CakeClient.ErrorType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The bug these pin down: only HttpServerErrorException and ResourceAccessException were
 * caught, so a 4xx escaped CakeClient as a raw HttpClientErrorException. PartyPlanner
 * catches CakeException, so it missed it entirely - no span status, no counter, no stored
 * party, and a 500 from an endpoint that had a considered answer available.
 */
class CakeClientTest {

    private static final String URL = "http://cake-service/cakes";
    private static final String A_CAKE =
            "{\"cakeId\":\"c1\",\"flavor\":\"chocolate\",\"candles\":10,\"bakeTimeMillis\":400}";

    private RestClient.Builder builder;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
    }

    private CakeClient client(int maxAttempts) {
        return new CakeClient(builder, "http://cake-service", maxAttempts);
    }

    @Test
    @DisplayName("a successful order returns the cake")
    void successReturnsCake() {
        server.expect(requestTo(URL)).andRespond(withSuccess(A_CAKE, MediaType.APPLICATION_JSON));

        Cake cake = client(3).order("party-1", "chocolate", 10);

        assertThat(cake.cakeId()).isEqualTo("c1");
        server.verify();
    }

    @Test
    @DisplayName("a 4xx becomes a CakeException instead of escaping unhandled")
    void clientErrorBecomesCakeException() {
        server.expect(once(), requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> client(3).order("party-1", "chocolate", 999))
                .isInstanceOf(CakeException.class)
                .extracting(e -> ((CakeException) e).errorType())
                .isEqualTo(ErrorType.REJECTED);
    }

    @Test
    @DisplayName("a 4xx is not retried, because repeating a bad request cannot help")
    void clientErrorIsNotRetried() {
        // once() is the assertion: a second call would fail verification.
        server.expect(once(), requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> client(3).order("party-1", "chocolate", 999))
                .isInstanceOf(CakeException.class);

        server.verify();
    }

    @Test
    @DisplayName("503 is retried up to maxAttempts, then reported as out of stock")
    void outOfStockIsRetried() {
        server.expect(times(3), requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client(3).order("party-1", "lemon", 10))
                .isInstanceOf(CakeException.class)
                .extracting(e -> ((CakeException) e).errorType())
                .isEqualTo(ErrorType.OUT_OF_STOCK);

        server.verify();
    }

    @Test
    @DisplayName("a retried order that eventually succeeds returns the cake")
    void retryThenSuccess() {
        server.expect(once(), requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(once(), requestTo(URL)).andRespond(withSuccess(A_CAKE, MediaType.APPLICATION_JSON));

        Cake cake = client(3).order("party-1", "lemon", 10);

        assertThat(cake.cakeId()).isEqualTo("c1");
        server.verify();
    }

    @Test
    @DisplayName("504 is not retried - the oven will not get faster")
    void ovenTimeoutIsNotRetried() {
        server.expect(once(), requestTo(URL)).andRespond(withStatus(HttpStatus.GATEWAY_TIMEOUT));

        assertThatThrownBy(() -> client(3).order("party-1", "chocolate", 99))
                .isInstanceOf(CakeException.class)
                .extracting(e -> ((CakeException) e).errorType())
                .isEqualTo(ErrorType.OVEN_TIMEOUT);

        server.verify();
    }

    @Test
    @DisplayName("an unreachable cake-service becomes a CakeException")
    void unreachableBecomesCakeException() {
        server.expect(once(), requestTo(URL))
                .andRespond(withException(new java.net.ConnectException("connection refused")));

        assertThatThrownBy(() -> client(3).order("party-1", "chocolate", 10))
                .isInstanceOf(CakeException.class)
                .extracting(e -> ((CakeException) e).errorType())
                .isEqualTo(ErrorType.UNREACHABLE);
    }

    @Test
    @DisplayName("a 2xx with no body is a failure, not a null cake")
    void emptyBodyBecomesCakeException() {
        server.expect(once(), requestTo(URL)).andRespond(withSuccess().contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client(3).order("party-1", "chocolate", 10))
                .isInstanceOf(CakeException.class)
                .extracting(e -> ((CakeException) e).errorType())
                .isEqualTo(ErrorType.EMPTY_RESPONSE);
    }

    @Test
    @DisplayName("an unexpected 5xx is classified rather than left unlabelled")
    void otherServerErrorIsClassified() {
        server.expect(once(), requestTo(URL)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> client(3).order("party-1", "chocolate", 10))
                .isInstanceOf(CakeException.class)
                .extracting(e -> ((CakeException) e).errorType())
                .isEqualTo(ErrorType.OTHER);
    }

    @Test
    @DisplayName("the error.type wire values are stable")
    void errorTypeValuesAreStable() {
        // These strings end up in dashboards and alerts, so they are part of the contract -
        // renaming an enum constant must not silently change what gets recorded.
        assertThat(ErrorType.OUT_OF_STOCK.value()).isEqualTo("out_of_stock");
        assertThat(ErrorType.OVEN_TIMEOUT.value()).isEqualTo("oven_timeout");
        assertThat(ErrorType.REJECTED.value()).isEqualTo("cake_rejected");
        assertThat(ErrorType.UNREACHABLE.value()).isEqualTo("cake_service_unreachable");
        assertThat(ErrorType.EMPTY_RESPONSE.value()).isEqualTo("empty_response");
        assertThat(ErrorType.INTERRUPTED.value()).isEqualTo("interrupted");
        // Semconv's reserved value for an unrecognised error, capitalised exactly so.
        assertThat(ErrorType.OTHER.value()).isEqualTo("_OTHER");
    }

    @Test
    @DisplayName("maxAttempts below 1 is rejected at construction, not at request time")
    void zeroMaxAttemptsIsRejected() {
        // The old loop body never ran with maxAttempts = 0, then dereferenced a null to
        // build the failure message - an NPE instead of a CakeException.
        assertThatThrownBy(() -> client(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-attempts");
    }
}
