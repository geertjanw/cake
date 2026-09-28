package com.cakeandcandles.time;

import java.time.Duration;

/**
 * Sleeps, without leaving the thread's interrupt flag on the floor.
 *
 * <p>Thread.sleep() clears the interrupt flag when it throws, so a bare
 * {@code catch (InterruptedException e) {}} silently swallows a cancellation request and
 * the thread carries on as if nothing happened. The contract is to either propagate the
 * InterruptedException or restore the flag before throwing something else, so whoever owns
 * the thread can still see that someone asked it to stop. This does the latter.
 */
public final class Sleeper {

    private Sleeper() {
    }

    /**
     * @throws IllegalStateException if interrupted, with the interrupt flag restored first.
     */
    public static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while sleeping for " + duration, e);
        }
    }
}
