package io.vidocq.humboldt.sdk.common;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClockTest {

    @Test
    void system_clock_now_is_close_to_instant_now() {
        long before = Instant.now().toEpochMilli();
        long now = Clock.system().now();
        long after = Instant.now().toEpochMilli();

        long nowMillis = now / 1_000_000L;
        assertTrue(nowMillis >= before - 5, "now() must be >= before: " + nowMillis + " vs " + before);
        assertTrue(nowMillis <= after + 5, "now() must be <= after: " + nowMillis + " vs " + after);
    }

    @Test
    void system_clock_nanoTime_is_monotonic() {
        long t1 = Clock.system().nanoTime();
        long t2 = Clock.system().nanoTime();
        assertTrue(t2 >= t1, "nanoTime() must be monotonic");
    }

    @Test
    void system_singleton_is_stable() {
        assertNotEquals(0, Clock.system().now());
        // same instance returned on each call
        org.junit.jupiter.api.Assertions.assertSame(Clock.system(), Clock.system());
    }
}
