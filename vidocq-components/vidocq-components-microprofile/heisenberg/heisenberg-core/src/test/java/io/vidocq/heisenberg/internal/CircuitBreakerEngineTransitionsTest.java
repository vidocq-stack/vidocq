/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.heisenberg.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.temporal.ChronoUnit;
import org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException;
import org.junit.jupiter.api.Test;

/** MP FT 4.1 §5: CLOSED → OPEN → HALF_OPEN → CLOSED/OPEN transitions and the failOn/skipOn rules. */
class CircuitBreakerEngineTransitionsTest {

    private static final String BEAN = "io.example.Breaker";
    private static final String METHOD = "call";
    private static final String OK = "ok";

    /** A registry whose clock is driven by the test: no sleeping. */
    private static final class ClockRegistry extends CircuitBreakerEngineTest.TestRegistry {
        long millisSinceOpen;

        @Override
        public long getMillisSinceOpen(String bc, String mn) {
            return millisSinceOpen;
        }
    }

    private final ClockRegistry registry = new ClockRegistry();

    private static CircuitBreakerConfig config(int volume, double ratio, int successThreshold,
                                               Class<?>[] failOn, Class<?>[] skipOn) {
        @SuppressWarnings("unchecked")
        Class<? extends Throwable>[] fail = (Class<? extends Throwable>[]) failOn;
        @SuppressWarnings("unchecked")
        Class<? extends Throwable>[] skip = (Class<? extends Throwable>[]) skipOn;
        return new CircuitBreakerConfig(volume, ratio, 100, ChronoUnit.MILLIS, successThreshold, fail, skip);
    }

    private static Object fail(RuntimeException failure) {
        throw failure;
    }

    private CircuitBreakerEngine openedEngine(int successThreshold, Class<?>[] skipOn) {
        CircuitBreakerEngine engine = new CircuitBreakerEngine(
                config(1, 1.0, successThreshold, new Class[]{RuntimeException.class}, skipOn), registry);
        assertThrows(IllegalStateException.class,
                () -> engine.execute(() -> fail(new IllegalStateException("boom")), BEAN, METHOD));
        assertEquals(CircuitBreakerState.OPEN, registry.getState(BEAN, METHOD));
        return engine;
    }

    @Test
    void openCircuitFailsFastUntilTheDelayElapses() {
        CircuitBreakerEngine engine = openedEngine(1, new Class[0]);
        registry.millisSinceOpen = 40;

        CircuitBreakerOpenException thrown = assertThrows(CircuitBreakerOpenException.class,
                () -> engine.execute(() -> OK, BEAN, METHOD));

        assertTrue(thrown.getMessage().contains("60 ms"), thrown::getMessage);
    }

    @Test
    void halfOpenClosesAfterTheSuccessThreshold() throws Exception {
        CircuitBreakerEngine engine = openedEngine(2, new Class[0]);
        registry.millisSinceOpen = 100;

        assertEquals(OK, engine.execute(() -> OK, BEAN, METHOD));
        assertEquals(CircuitBreakerState.HALF_OPEN, registry.getState(BEAN, METHOD));

        assertEquals(OK, engine.execute(() -> OK, BEAN, METHOD));
        assertEquals(CircuitBreakerState.CLOSED, registry.getState(BEAN, METHOD));
    }

    @Test
    void failureInHalfOpenReopensTheCircuit() throws Exception {
        CircuitBreakerEngine engine = openedEngine(2, new Class[0]);
        registry.millisSinceOpen = 150;
        assertEquals(OK, engine.execute(() -> OK, BEAN, METHOD));

        assertThrows(IllegalStateException.class,
                () -> engine.execute(() -> fail(new IllegalStateException("again")), BEAN, METHOD));

        assertEquals(CircuitBreakerState.OPEN, registry.getState(BEAN, METHOD));
    }

    @Test
    void skippedFailureInHalfOpenKeepsTheCircuitHalfOpen() throws Exception {
        CircuitBreakerEngine engine = openedEngine(2, new Class[]{UnsupportedOperationException.class});
        registry.millisSinceOpen = 100;
        assertEquals(OK, engine.execute(() -> OK, BEAN, METHOD));

        assertThrows(UnsupportedOperationException.class,
                () -> engine.execute(() -> fail(new UnsupportedOperationException("skip")), BEAN, METHOD));

        assertEquals(CircuitBreakerState.HALF_OPEN, registry.getState(BEAN, METHOD));
    }

    @Test
    void skipOnFailuresCountAsSuccessesInClosed() {
        CircuitBreakerEngine engine = new CircuitBreakerEngine(
                config(2, 0.5, 1, new Class[]{RuntimeException.class}, new Class[]{IllegalArgumentException.class}),
                registry);

        for (int i = 0; i < 4; i++) {
            assertThrows(IllegalArgumentException.class,
                    () -> engine.execute(() -> fail(new IllegalArgumentException("skipped")), BEAN, METHOD));
        }

        assertEquals(CircuitBreakerState.CLOSED, registry.getState(BEAN, METHOD));
    }

    @Test
    void failuresOutsideFailOnDoNotOpenTheCircuit() {
        CircuitBreakerEngine engine = new CircuitBreakerEngine(
                config(1, 0.5, 1, new Class[]{IOException.class}, new Class[0]), registry);

        assertThrows(IllegalStateException.class,
                () -> engine.execute(() -> fail(new IllegalStateException("other")), BEAN, METHOD));

        assertEquals(CircuitBreakerState.CLOSED, registry.getState(BEAN, METHOD));
    }

    @Test
    void slidingWindowForgetsTheOldestOutcomes() throws Exception {
        CircuitBreakerEngine engine = new CircuitBreakerEngine(
                config(2, 1.0, 1, new Class[]{RuntimeException.class}, new Class[0]), registry);
        RuntimeException failure = new IllegalStateException("f");

        assertThrows(IllegalStateException.class, () -> engine.execute(() -> fail(failure), BEAN, METHOD));
        engine.execute(() -> OK, BEAN, METHOD);
        engine.execute(() -> OK, BEAN, METHOD);
        assertThrows(IllegalStateException.class, () -> engine.execute(() -> fail(failure), BEAN, METHOD));
        assertEquals(CircuitBreakerState.CLOSED, registry.getState(BEAN, METHOD));

        assertThrows(IllegalStateException.class, () -> engine.execute(() -> fail(failure), BEAN, METHOD));

        assertEquals(CircuitBreakerState.OPEN, registry.getState(BEAN, METHOD));
    }
}
