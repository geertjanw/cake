package com.cakeandcandles.time;

import java.time.Duration;

/**
/**
 * A monotonic stopwatch for measuring how long something took.
 *
 * <p>System.nanoTime() is the JDK's only monotonic clock. Instant, Clock and friends read
 * the wall clock, which NTP can step backwards - and a latency histogram must never see a
 * negative sample.</p>
 */
public record Stopwatch(long startNanos) {

    private static final double NANOS_PER_SECOND = 1_000_000_000.0;

    public static Stopwatch start() {
        return new Stopwatch(System.nanoTime());
    }

    public Duration elapsed() {
        return Duration.ofNanos(System.nanoTime() - startNanos);
    }

    /**
     * Elapsed time as fractional seconds, the unit OpenTelemetry uses for durations.
     * Duration stores seconds-plus-nanos but exposes no fractional accessor - every getter
     * is integral and toSeconds() truncates - so the two halves are recombined by hand.
     */
    public double elapsedSeconds() {
        Duration current = elapsed();
        return current.getSeconds() + current.getNano() / NANOS_PER_SECOND;
    }
}
