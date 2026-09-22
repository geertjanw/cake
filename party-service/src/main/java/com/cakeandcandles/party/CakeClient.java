package com.cakeandcandles.party;

import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Talks to cake-service. Retries when the bakery is out of stock (503) with a short
 * backoff - the retries show up as sibling HTTP client spans in the same trace.
 * An oven timeout (504) is not retried; the cake just isn't happening.
 */
@Component
public class CakeClient {

    private static final Logger log = LoggerFactory.getLogger(CakeClient.class);

    public record Cake(String cakeId, String flavor, int candles, long bakeTimeMillis) {}

    public static class CakeException extends RuntimeException {
        public CakeException(String message, Throwable cause) { super(message, cause); }
    }

    private final RestClient http;
    private final int maxAttempts;

    public CakeClient(RestClient.Builder builder,
                      @Value("${clients.cake.url}") String baseUrl,
                      @Value("${clients.cake.max-attempts:3}") int maxAttempts) {
        this.http = builder.baseUrl(baseUrl).build();
        this.maxAttempts = maxAttempts;
    }

    public Cake order(String partyId, String flavor, int candles) {
        Map<String, Object> body = Map.of("partyId", partyId, "flavor", flavor, "candles", candles);
        HttpServerErrorException last = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                Span.current().addEvent("ordering cake, attempt " + attempt);
                return http.post().uri("/cakes").body(body).retrieve().body(Cake.class);
            } catch (HttpServerErrorException e) {
                last = e;
                if (e.getStatusCode() == HttpStatus.SERVICE_UNAVAILABLE && attempt < maxAttempts) {
                    log.warn("Bakery out of {} (attempt {}/{}), retrying", flavor, attempt, maxAttempts);
                    sleep(200L * attempt);
                    continue;
                }
                break;
            } catch (ResourceAccessException e) {
                throw new CakeException("cake-service unreachable: " + e.getMessage(), e);
            }
        }
        throw new CakeException("cake order failed after " + maxAttempts + " attempt(s): "
                + last.getStatusCode() + " " + last.getResponseBodyAsString(), last);
    }

    private static void sleep(long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
