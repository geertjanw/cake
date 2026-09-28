package com.cakeandcandles.time;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Timing tests are a classic source of flakiness, so every assertion here is one-sided in
 * the direction time actually moves. Lower bounds are exact: a stopwatch started N nanos in
 * the past has certainly elapsed at least N. Upper bounds are deliberately loose, because a
 * GC pause or a loaded CI box can stretch any of these arbitrarily - they only exist to
 * catch order-of-magnitude unit errors (milliseconds reported as seconds, say).
 */
class StopwatchTest {

    /** Generous enough that only a wrong unit, not a slow machine, can trip it. */
    private static final double ABSURDLY_LONG_SECONDS = 60.0;

    private static Stopwatch startedAgo(Duration ago) {
        return new Stopwatch(System.nanoTime() - ago.toNanos());
    }

    @Test
    @DisplayName("a freshly started stopwatch has not gone backwards")
    void freshStopwatchIsNotNegative() {
        Stopwatch timer = Stopwatch.start();

        assertThat(timer.elapsed()).isGreaterThanOrEqualTo(Duration.ZERO);
        assertThat(timer.elapsedSeconds()).isGreaterThanOrEqualTo(0.0);
    }

    @Test
    @DisplayName("elapsed() never decreases between readings")
    void elapsedIsMonotonic() {
        Stopwatch timer = Stopwatch.start();

        Duration first = timer.elapsed();
        Duration second = timer.elapsed();
        Duration third = timer.elapsed();

        assertThat(second).isGreaterThanOrEqualTo(first);
        assertThat(third).isGreaterThanOrEqualTo(second);
    }

    @Test
    @DisplayName("elapsed() reports at least the time that has actually passed")
    void elapsedReflectsElapsedTime() {
        Stopwatch timer = startedAgo(Duration.ofMillis(250));

        assertThat(timer.elapsed()).isGreaterThanOrEqualTo(Duration.ofMillis(250));
        assertThat(timer.elapsed()).isLessThan(Duration.ofSeconds(30));
    }

    @Test
    @DisplayName("elapsedSeconds() keeps the fractional part rather than truncating")
    void elapsedSecondsKeepsTheFraction() {
        // The regression this guards: Duration.toSeconds() truncates, so an implementation
        // built on it would report 2.0 here and quietly discard 750ms from every sample.
        Stopwatch timer = startedAgo(Duration.ofMillis(2_750));

        assertThat(timer.elapsedSeconds())
                .isGreaterThanOrEqualTo(2.75)
                .isLessThan(ABSURDLY_LONG_SECONDS);
    }

    @Test
    @DisplayName("elapsedSeconds() resolves sub-second durations")
    void elapsedSecondsHandlesSubSecondDurations() {
        // A whole-seconds implementation would report 0.0 for every bake faster than a
        // second - which is most of them.
        Stopwatch timer = startedAgo(Duration.ofMillis(120));

        assertThat(timer.elapsedSeconds())
                .isGreaterThanOrEqualTo(0.120)
                .isLessThan(ABSURDLY_LONG_SECONDS);
    }

    @Test
    @DisplayName("elapsedSeconds() is in seconds, agreeing with elapsed()")
    void elapsedSecondsAgreesWithElapsed() {
        Stopwatch timer = startedAgo(Duration.ofMillis(500));

        // Bracket the reading: elapsedSeconds() is taken between two elapsed() readings, so
        // it must fall between them. This is what catches a unit mix-up - a millisecond
        // value would land three orders of magnitude outside the bracket.
        double before = timer.elapsed().toNanos() / 1_000_000_000.0;
        double seconds = timer.elapsedSeconds();
        double after = timer.elapsed().toNanos() / 1_000_000_000.0;

        assertThat(seconds).isBetween(before, after);
    }

    @Test
    @DisplayName("a stopwatch with no time elapsed reports zero, not a truncated value")
    void zeroElapsedIsZero() {
        // Constructed directly at "now" so the delta is as close to zero as the clock allows.
        Stopwatch timer = new Stopwatch(System.nanoTime());

        assertThat(timer.elapsedSeconds()).isCloseTo(0.0, within(1.0));
        assertThat(timer.elapsed()).isGreaterThanOrEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("start() anchors to the current monotonic clock, not the epoch")
    void startAnchorsToNanoTime() {
        // A stopwatch anchored to the wall clock (e.g. currentTimeMillis) would report
        // decades of elapsed time here, since the two clocks share no origin.
        long before = System.nanoTime();
        Stopwatch timer = Stopwatch.start();
        long after = System.nanoTime();

        assertThat(timer.startNanos()).isBetween(before, after);
    }
}
