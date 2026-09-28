package com.cakeandcandles.party;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.semconv.ErrorAttributes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Map;

/**
 * Talks to cake-service. Retries when the bakery is out of stock (503) with a short
 * backoff - the retries show up as sibling HTTP client spans in the same trace.
 * An oven timeout (504) is not retried; the cake just isn't happening.
 */
@Component
public class CakeClient {

    private static final Logger log = LoggerFactory.getLogger(CakeClient.class);

    private static final AttributeKey<Long> ATTEMPTS = AttributeKey.longKey("cake.order.attempts");

    /**
     * Every way a cake order can fail, and the {@code error.type} value for each.
     *
     * <p>An enum rather than loose strings precisely because these end up as metric
     * attributes: the set of time series a failing order can create is now whatever is
     * listed here, and the compiler will not let a call site invent a new one. The wire
     * values are spelled out instead of derived from the constant names, so renaming a
     * constant cannot silently orphan a dashboard.
     */
    public enum ErrorType {
        /** cake-service has no stock, and retrying did not help. */
        OUT_OF_STOCK("out_of_stock"),
        /** Too many candles for the oven. Not retryable. */
        OVEN_TIMEOUT("oven_timeout"),
        /** 4xx: cake-service is fine, our request is not. */
        REJECTED("cake_rejected"),
        /** cake-service could not be reached at all. */
        UNREACHABLE("cake_service_unreachable"),
        /** A 2xx carrying no cake. */
        EMPTY_RESPONSE("empty_response"),
        /** The thread was interrupted while backing off between retries. */
        INTERRUPTED("interrupted"),
        /** Anything unrecognised. Semconv reserves "_OTHER" for exactly this. */
        OTHER(ErrorAttributes.ErrorTypeValues.OTHER);

        private final String value;

        ErrorType(String value) {
            this.value = value;
        }

        /** The value to record as {@code error.type}. */
        public String value() {
            return value;
        }
    }

    public record Cake(String cakeId, String flavor, int candles, long bakeTimeMillis) {}

    public static class CakeException extends RuntimeException {

        private final ErrorType errorType;

        public CakeException(ErrorType errorType, String message, Throwable cause) {
            super(message, cause);
            this.errorType = errorType;
        }

        public ErrorType errorType() {
            return errorType;
        }
    }

    private final RestClient http;
    private final int maxAttempts;

    public CakeClient(RestClient.Builder builder,
                      @Value("${clients.cake.url}") String baseUrl,
                      @Value("${clients.cake.max-attempts:3}") int maxAttempts) {
        if (maxAttempts < 1) {
            // Otherwise the retry loop never runs and every order fails with no attempt
            // ever made - a misconfiguration worth failing at startup, not at request time.
            throw new IllegalArgumentException("clients.cake.max-attempts must be at least 1");
        }
        this.http = builder.baseUrl(baseUrl).build();
        this.maxAttempts = maxAttempts;
    }

    public Cake order(String partyId, String flavor, int candles) {
        Map<String, Object> body = Map.of("partyId", partyId, "flavor", flavor, "candles", candles);

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            Span.current().addEvent("ordering cake, attempt " + attempt);
            try {
                Cake cake = http.post().uri("/cakes").body(body).retrieve().body(Cake.class);
                if (cake == null) {
                    // A 2xx with no body. Returning null would hand PartyPlanner a party
                    // with no cake id and fail later, somewhere less obvious.
                    throw fail(ErrorType.EMPTY_RESPONSE, attempt, "cake-service returned an empty body", null);
                }
                // How many attempts this took, on the span that ordered it. Without this the
                // only evidence of a retry is counting sibling HTTP spans by hand.
                Span.current().setAttribute(ATTEMPTS, (long) attempt);
                return cake;
            } catch (HttpServerErrorException e) {
                if (e.getStatusCode() == HttpStatus.SERVICE_UNAVAILABLE && attempt < maxAttempts) {
                    log.warn("Bakery out of {} (attempt {}/{}), retrying", flavor, attempt, maxAttempts);
                    backOff(200L * attempt, attempt);
                    continue;
                }
                throw fail(serverErrorType(e.getStatusCode()), attempt,
                        "cake order failed: " + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
            } catch (HttpClientErrorException e) {
                // 4xx: cake-service is fine, our request is not - too many candles, say.
                // Retrying would just repeat the same mistake more expensively.
                throw fail(ErrorType.REJECTED, attempt,
                        "cake-service rejected the order: " + e.getStatusCode() + " "
                                + e.getResponseBodyAsString(), e);
            } catch (ResourceAccessException e) {
                throw fail(ErrorType.UNREACHABLE, attempt, "cake-service unreachable: " + e.getMessage(), e);
            } catch (RestClientException e) {
                // The catch-all that stops anything new - an unparseable body, a redirect
                // loop - from escaping this class unclassified.
                throw fail(ErrorType.OTHER, attempt,
                        "cake order failed: " + e.getMessage(), e);
            }
        }

        // Unreachable: the loop only continues while attempt < maxAttempts, and every other
        // path returns or throws. Here so a future edit to the loop cannot fall through.
        throw fail(ErrorType.OTHER, maxAttempts,
                "cake order abandoned after " + maxAttempts + " attempt(s)", null);
    }

    /** Records the attempt count before throwing, so failed orders are comparable to successful ones. */
    private static CakeException fail(ErrorType errorType, int attempts, String message, Exception cause) {
        Span.current().setAttribute(ATTEMPTS, (long) attempts);
        return new CakeException(errorType, message, cause);
    }

    private static ErrorType serverErrorType(HttpStatusCode status) {
        if (status.isSameCodeAs(HttpStatus.SERVICE_UNAVAILABLE)) return ErrorType.OUT_OF_STOCK;
        if (status.isSameCodeAs(HttpStatus.GATEWAY_TIMEOUT)) return ErrorType.OVEN_TIMEOUT;
        return ErrorType.OTHER;
    }

    /**
     * Sleeps between retries, and gives up if someone asked this thread to stop. Restoring
     * the flag but carrying on would leave every later sleep throwing immediately, turning
     * the backoff into a spin.
     */
    private static void backOff(long millis, int attempt) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw fail(ErrorType.INTERRUPTED, attempt, "interrupted while waiting to retry the cake order", e);
        }
    }
}
