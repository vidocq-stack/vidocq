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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.temporal.ChronoUnit;
import org.eclipse.microprofile.faulttolerance.ExecutionContext;
import org.eclipse.microprofile.faulttolerance.FallbackHandler;
import org.junit.jupiter.api.Test;

/**
 * The policy configuration records carry exception-type arrays: two configurations built from the
 * same values must be equal, hash alike and print their array contents.
 */
class PolicyConfigValueSemanticsTest {

    private static final String IO_EXCEPTION = "java.io.IOException";

    @SuppressWarnings("unchecked")
    private static Class<? extends Throwable>[] types(Class<?>... types) {
        Class<? extends Throwable>[] array = new Class[types.length];
        for (int i = 0; i < types.length; i++) {
            array[i] = (Class<? extends Throwable>) types[i];
        }
        return array;
    }

    private static RetryConfig retry(Class<?>... retryOn) {
        return new RetryConfig(3, 0, ChronoUnit.MILLIS, 1000, ChronoUnit.MILLIS, 0, ChronoUnit.MILLIS,
                types(retryOn), types(IllegalStateException.class));
    }

    private static CircuitBreakerConfig circuitBreaker(Class<?>... failOn) {
        return new CircuitBreakerConfig(4, 0.5, 100, ChronoUnit.MILLIS, 2, types(failOn), types());
    }

    private static FallbackConfig fallback(Class<?>... applyOn) {
        return new FallbackConfig(types(applyOn), types(IllegalStateException.class), "recover", null);
    }

    @Test
    void retryConfigComparesArrayContents() {
        RetryConfig first = retry(IOException.class);
        RetryConfig second = retry(IOException.class);

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertNotEquals(first, retry(RuntimeException.class));
        assertNotEquals(first, new RetryConfig(4, 0, ChronoUnit.MILLIS, 1000, ChronoUnit.MILLIS, 0,
                ChronoUnit.MILLIS, types(IOException.class), types(IllegalStateException.class)));
        assertTrue(first.toString().contains(IO_EXCEPTION), first::toString);
    }

    @Test
    void circuitBreakerConfigComparesArrayContents() {
        CircuitBreakerConfig first = circuitBreaker(IOException.class);
        CircuitBreakerConfig second = circuitBreaker(IOException.class);

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertNotEquals(first, circuitBreaker(RuntimeException.class));
        assertNotEquals(first, CircuitBreakerConfig.DEFAULT);
        assertTrue(first.toString().contains(IO_EXCEPTION), first::toString);
    }

    @Test
    void fallbackConfigComparesArrayContents() {
        FallbackConfig first = fallback(IOException.class);
        FallbackConfig second = fallback(IOException.class);

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertNotEquals(first, fallback(RuntimeException.class));
        assertNotEquals(first, new FallbackConfig(types(IOException.class), types(IllegalStateException.class),
                "other", null));
        assertTrue(first.toString().contains(IO_EXCEPTION), first::toString);
    }

    @Test
    void retryConfigDiffersOnEveryComponent() {
        RetryConfig base = retry(IOException.class);
        Class<? extends Throwable>[] io = types(IOException.class);
        Class<? extends Throwable>[] state = types(IllegalStateException.class);

        assertNotEquals(null, base);
        assertNotEquals("RetryConfig", base);
        assertNotEquals(base, new RetryConfig(3, 1, ChronoUnit.MILLIS, 1000, ChronoUnit.MILLIS, 0, ChronoUnit.MILLIS, io, state));
        assertNotEquals(base, new RetryConfig(3, 0, ChronoUnit.MILLIS, 999, ChronoUnit.MILLIS, 0, ChronoUnit.MILLIS, io, state));
        assertNotEquals(base, new RetryConfig(3, 0, ChronoUnit.MILLIS, 1000, ChronoUnit.MILLIS, 1, ChronoUnit.MILLIS, io, state));
        assertNotEquals(base, new RetryConfig(3, 0, ChronoUnit.SECONDS, 1000, ChronoUnit.MILLIS, 0, ChronoUnit.MILLIS, io, state));
        assertNotEquals(base, new RetryConfig(3, 0, ChronoUnit.MILLIS, 1000, ChronoUnit.SECONDS, 0, ChronoUnit.MILLIS, io, state));
        assertNotEquals(base, new RetryConfig(3, 0, ChronoUnit.MILLIS, 1000, ChronoUnit.MILLIS, 0, ChronoUnit.SECONDS, io, state));
        assertNotEquals(base, new RetryConfig(3, 0, ChronoUnit.MILLIS, 1000, ChronoUnit.MILLIS, 0, ChronoUnit.MILLIS, io, io));
    }

    @Test
    void circuitBreakerConfigDiffersOnEveryComponent() {
        Class<? extends Throwable>[] io = types(IOException.class);
        Class<? extends Throwable>[] none = types();
        CircuitBreakerConfig base = new CircuitBreakerConfig(4, 0.5, 100, ChronoUnit.MILLIS, 2, io, none);

        assertNotEquals(null, base);
        assertNotEquals("CircuitBreakerConfig", base);
        assertNotEquals(base, new CircuitBreakerConfig(5, 0.5, 100, ChronoUnit.MILLIS, 2, io, none));
        assertNotEquals(base, new CircuitBreakerConfig(4, 0.75, 100, ChronoUnit.MILLIS, 2, io, none));
        assertNotEquals(base, new CircuitBreakerConfig(4, 0.5, 101, ChronoUnit.MILLIS, 2, io, none));
        assertNotEquals(base, new CircuitBreakerConfig(4, 0.5, 100, ChronoUnit.SECONDS, 2, io, none));
        assertNotEquals(base, new CircuitBreakerConfig(4, 0.5, 100, ChronoUnit.MILLIS, 3, io, none));
        assertNotEquals(base, new CircuitBreakerConfig(4, 0.5, 100, ChronoUnit.MILLIS, 2, io, io));
    }

    @Test
    void fallbackConfigDiffersOnEveryComponent() {
        Class<? extends Throwable>[] io = types(IOException.class);
        Class<? extends Throwable>[] state = types(IllegalStateException.class);
        FallbackConfig base = new FallbackConfig(io, state, "recover", null);

        assertNotEquals(null, base);
        assertNotEquals("FallbackConfig", base);
        assertNotEquals(base, new FallbackConfig(io, io, "recover", null));
        assertNotEquals(base, new FallbackConfig(io, state, "recover", NoopHandler.class));
    }

    public static class NoopHandler implements FallbackHandler<String> {
        @Override
        public String handle(ExecutionContext context) {
            return "noop";
        }
    }
}
