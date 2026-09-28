package com.cakeandcandles.cake;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.Set;

/**
 * Two deliberate failure modes: out of stock (retryable) and a slow oven (not retryable).
 * Plus one plain input error: a flavor we do not serve.
 */
public final class BakeryExceptions {

    private BakeryExceptions() {
    }

    public static class OutOfStockException extends RuntimeException {
        public OutOfStockException(String flavor) {
            super("We are out of " + flavor + " - please restock");
        }
    }

    /**
     * A flavor outside the served set. Rejected rather than sanitised, because the value
     * ends up as a metric attribute and an unbounded attribute is an unbounded number of
     * time series. Note the served set, not the rejected value, goes in the message.
     */
    public static class UnknownFlavorException extends RuntimeException {
        public UnknownFlavorException(String flavor, Set<String> served) {
            super("We do not bake " + flavor + " cakes - we serve " + String.join(", ", served));
        }
    }

    public static class OvenTimeoutException extends RuntimeException {
        public OvenTimeoutException(int candles) {
            super("Oven timed out: " + candles + " candles is too many for our little oven");
        }
    }

    @RestControllerAdvice
    public static class Handler {
        // 400, not 5xx: the request is wrong, the bakery is fine. Server spans are not
        // marked as errors for 4xx, so a stream of bad flavors will not look like an outage.
        @ExceptionHandler(UnknownFlavorException.class)
        @ResponseStatus(HttpStatus.BAD_REQUEST)
        public Map<String, String> unknownFlavor(UnknownFlavorException e) {
            return Map.of("error", "unknown_flavor", "message", e.getMessage());
        }

        @ExceptionHandler(OutOfStockException.class)
        @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
        public Map<String, String> outOfStock(OutOfStockException e) {
            return Map.of("error", "out_of_stock", "message", e.getMessage());
        }

        @ExceptionHandler(OvenTimeoutException.class)
        @ResponseStatus(HttpStatus.GATEWAY_TIMEOUT)
        public Map<String, String> ovenTimeout(OvenTimeoutException e) {
            return Map.of("error", "oven_timeout", "message", e.getMessage());
        }
    }
}
