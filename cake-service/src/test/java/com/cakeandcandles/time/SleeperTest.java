package com.cakeandcandles.time;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SleeperTest {

    @AfterEach
    void clearInterruptFlag() {
        // Leaving it set would make every later test on this thread fail in confusing ways.
        Thread.interrupted();
    }

    @Test
    @DisplayName("sleeps for at least the requested duration")
    void sleepsForAtLeastTheRequestedDuration() {
        Stopwatch timer = Stopwatch.start();

        Sleeper.sleep(Duration.ofMillis(50));

        assertThat(timer.elapsed()).isGreaterThanOrEqualTo(Duration.ofMillis(50));
    }

    @Test
    @DisplayName("a zero duration returns immediately rather than sleeping forever")
    void zeroDurationReturnsImmediately() {
        // Thread.sleep(0) is a yield, not an eternity - worth pinning, since the opposite
        // reading of "sleep(0)" exists in other APIs.
        Stopwatch timer = Stopwatch.start();

        Sleeper.sleep(Duration.ZERO);

        assertThat(timer.elapsed()).isLessThan(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("an interrupted sleep throws instead of returning early and pretending")
    void interruptedSleepThrows() {
        Thread.currentThread().interrupt();

        assertThatThrownBy(() -> Sleeper.sleep(Duration.ofMinutes(10)))
                .isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(InterruptedException.class);
    }

    @Test
    @DisplayName("an interrupted sleep restores the interrupt flag")
    void interruptedSleepRestoresTheFlag() {
        // The bug this guards: Thread.sleep() clears the flag when it throws, so catching
        // InterruptedException without restoring it swallows the cancellation request and
        // the thread carries on as though nobody had asked it to stop.
        Thread.currentThread().interrupt();

        assertThatThrownBy(() -> Sleeper.sleep(Duration.ofMinutes(10)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }
}
