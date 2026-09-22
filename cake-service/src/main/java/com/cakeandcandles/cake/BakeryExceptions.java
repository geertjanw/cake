package com.cakeandcandles.cake;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** Two deliberate failure modes: out of stock (retryable) and a slow oven (not retryable). */
public class BakeryExceptions {

    public static class OutOfStockException extends RuntimeException {
        public OutOfStockException(String flavor) {
            super("We are out of " + flavor + " - please restock");
        }
    }

    public static class OvenTimeoutException extends RuntimeException {
        public OvenTimeoutException(int candles) {
            super("Oven timed out: " + candles + " candles is too many for our little oven");
        }
    }

    @RestControllerAdvice
    public static class Handler {
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
