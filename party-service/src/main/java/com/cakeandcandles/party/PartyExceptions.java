package com.cakeandcandles.party;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.Set;

/** Input errors that party-service can answer without troubling the bakery. */
public final class PartyExceptions {

    private PartyExceptions() {
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
    }
}
