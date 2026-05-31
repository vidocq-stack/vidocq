package io.vidocq.heisenberg.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.time.temporal.ChronoUnit;
import org.eclipse.microprofile.faulttolerance.exceptions.TimeoutException;
import org.junit.jupiter.api.Test;

class TimeoutEngineTest {

    @Test
    void executionCompletesBeforeDeadlineReturnsResult() throws Exception {
        // §4: if the invocation finishes before the deadline, the result is returned normally.
        TimeoutConfig config = new TimeoutConfig(500, ChronoUnit.MILLIS);

        Object result = TimeoutEngine.execute(() -> "success", config);

        assertEquals("success", result);
    }

    @Test
    void executionExceedsDeadlineThrowsTimeoutException() {
        // §4: if the invocation exceeds the timeout value, the MP FT TimeoutException is thrown.
        TimeoutConfig config = new TimeoutConfig(100, ChronoUnit.MILLIS);

        assertThrows(TimeoutException.class, () ->
                TimeoutEngine.execute(() -> {
                    Thread.sleep(600);
                    return "too-late";
                }, config)
        );
    }

    @Test
    void exceptionFromInvocationIsPropagatedBeforeDeadline() {
        // §4: exceptions thrown before the deadline are propagated as-is.
        TimeoutConfig config = new TimeoutConfig(500, ChronoUnit.MILLIS);

        IOException ex = assertThrows(IOException.class, () ->
                TimeoutEngine.execute(() -> {
                    throw new IOException("backend error");
                }, config)
        );

        assertEquals("backend error", ex.getMessage());
    }

    @Test
    void checkedExceptionFromInvocationIsPropagatedBeforeDeadline() throws Exception {
        // §4: a checked exception (other than timeout) is propagated directly.
        TimeoutConfig config = new TimeoutConfig(500, ChronoUnit.MILLIS);

        assertThrows(IllegalStateException.class, () ->
                TimeoutEngine.execute(() -> {
                    throw new IllegalStateException("state error");
                }, config)
        );
    }

    @Test
    void timeoutConfigDefaultIs1000ms() {
        // Verify the DEFAULT constant (spec value §4).
        assertEquals(1_000L, TimeoutConfig.DEFAULT.value());
        assertEquals(ChronoUnit.MILLIS, TimeoutConfig.DEFAULT.unit());
    }

    @Test
    void timeoutConfigRejectsNonPositiveValue() {
        assertThrows(IllegalArgumentException.class, () -> new TimeoutConfig(0, ChronoUnit.MILLIS));
        assertThrows(IllegalArgumentException.class, () -> new TimeoutConfig(-1, ChronoUnit.MILLIS));
    }

    @Test
    void timeoutConfigDurationIsCoherent() {
        TimeoutConfig config = new TimeoutConfig(2, ChronoUnit.SECONDS);

        assertEquals(2_000L, config.duration().toMillis());
    }
}



