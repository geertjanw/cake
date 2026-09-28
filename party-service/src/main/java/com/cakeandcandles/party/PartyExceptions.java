package com.cakeandcandles.party;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.Set;

/** Bad requests, whether party-service spots them itself or the bakery does. */
public final class PartyExceptions {

    private PartyExceptions() {
    }

    /**
     * The bakery refused the order with a 4xx: the request was never bakeable - too many
     * candles, say. The party is not "failed", it was never valid, so this is answered the
     * same way as an unknown flavor rather than being reported as a downstream failure.
     */
    public static class CakeRejectedException extends RuntimeException {
        public CakeRejectedException(String detail, Throwable cause) {
            super("The bakery rejected this order: " + detail, cause);
        }
    }

    /**
     * A flavor the bakery does not serve. Rejected rather than sanitised, because the value
     * ends up as an attribute on parties.planned and an unbounded attribute is an unbounded
     * number of time series.
     */
    public static class UnknownFlavorException extends RuntimeException {
        public UnknownFlavorException(String flavor, Set<String> served) {
            super("No bakery bakes " + flavor + " cakes - available flavors are " + String.join(", ", served));
        }
    }

    @RestControllerAdvice
    public static class Handler {
        // 400, not 5xx: the request is wrong, nothing downstream is broken. Server spans
        // are not marked as errors for 4xx, so bad flavors will not look like an outage.
        @ExceptionHandler(UnknownFlavorException.class)
        @ResponseStatus(HttpStatus.BAD_REQUEST)
        public Map<String, String> unknownFlavor(UnknownFlavorException e) {
            return Map.of("error", "unknown_flavor", "message", e.getMessage());
        }

        // Also 400: a 502 here would say "the bakery is broken" when the bakery is fine and
        // working exactly as intended by refusing an impossible order.
        @ExceptionHandler(CakeRejectedException.class)
        @ResponseStatus(HttpStatus.BAD_REQUEST)
        public Map<String, String> cakeRejected(CakeRejectedException e) {
            return Map.of("error", "cake_rejected", "message", e.getMessage());
        }
    }
}
