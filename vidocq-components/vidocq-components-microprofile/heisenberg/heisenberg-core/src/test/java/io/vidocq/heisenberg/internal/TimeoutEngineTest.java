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
        // §4 : si l'invocation se termine avant la deadline, le résultat est retourné normalement.
        TimeoutConfig config = new TimeoutConfig(500, ChronoUnit.MILLIS);

        Object result = TimeoutEngine.execute(() -> "success", config);

        assertEquals("success", result);
    }

    @Test
    void executionExceedsDeadlineThrowsTimeoutException() {
        // §4 : si l'invocation dépasse la valeur de timeout, TimeoutException (MP FT) est levée.
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
        // §4 : les exceptions levées avant la deadline sont propagées telles quelles.
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
        // §4 : une exception checked (autre que timeout) est propagée directement.
        TimeoutConfig config = new TimeoutConfig(500, ChronoUnit.MILLIS);

        assertThrows(IllegalStateException.class, () ->
                TimeoutEngine.execute(() -> {
                    throw new IllegalStateException("state error");
                }, config)
        );
    }

    @Test
    void timeoutConfigDefaultIs1000ms() {
        // Vérification de la constante DEFAULT (valeur spec §4).
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



