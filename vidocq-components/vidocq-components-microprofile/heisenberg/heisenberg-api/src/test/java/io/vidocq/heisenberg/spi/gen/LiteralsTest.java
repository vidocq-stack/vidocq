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
package io.vidocq.heisenberg.spi.gen;

import java.io.IOException;
import java.time.temporal.ChronoUnit;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The literals a companion builds compare and print their array members by content, as annotations do. */
class LiteralsTest {

    static class Annotated {
        @Retry(maxRetries = 2, retryOn = IOException.class)
        @CircuitBreaker(failOn = IllegalStateException.class, failureRatio = 0.25)
        @Fallback(fallbackMethod = "recover", applyOn = IOException.class)
        void call() {
            // annotations only
        }
    }

    private static Retry retry(Class<? extends Throwable> retryOn) {
        return new Literals.RetryLiteral(2, 0L, ChronoUnit.MILLIS, 180_000L, ChronoUnit.MILLIS, 200L,
                ChronoUnit.MILLIS, array(retryOn), array());
    }

    @SafeVarargs
    private static Class<? extends Throwable>[] array(Class<? extends Throwable>... types) {
        return types;
    }

    @Test
    void aRetryLiteralEqualsTheAnnotationItStandsFor() throws Exception {
        Retry annotation = Annotated.class.getDeclaredMethod("call").getAnnotation(Retry.class);
        Retry literal = retry(IOException.class);
        assertEquals(literal, retry(IOException.class));
        assertEquals(literal.hashCode(), retry(IOException.class).hashCode());
        assertEquals(literal, annotation, "same members, arrays by content");
        assertNotEquals(literal, retry(RuntimeException.class));
        assertTrue(literal.toString().contains("retryOn=[class java.io.IOException]"), literal.toString());
        assertEquals(Retry.class, literal.annotationType());
    }

    @Test
    void aCircuitBreakerLiteralComparesItsArraysByContent() throws Exception {
        CircuitBreaker annotation = Annotated.class.getDeclaredMethod("call").getAnnotation(CircuitBreaker.class);
        CircuitBreaker literal = new Literals.CircuitBreakerLiteral(array(IllegalStateException.class), array(),
                5_000L, ChronoUnit.MILLIS, 20, 0.25, 1);
        assertEquals(literal, annotation);
        assertEquals(literal.hashCode(), new Literals.CircuitBreakerLiteral(array(IllegalStateException.class),
                array(), 5_000L, ChronoUnit.MILLIS, 20, 0.25, 1).hashCode());
        assertTrue(literal.toString().startsWith("@" + CircuitBreaker.class.getName() + "(failOn=["));
    }

    @Test
    void aFallbackLiteralComparesItsArraysByContent() throws Exception {
        Fallback annotation = Annotated.class.getDeclaredMethod("call").getAnnotation(Fallback.class);
        Fallback literal = new Literals.FallbackLiteral(Fallback.DEFAULT.class, "recover", array(IOException.class),
                array());
        assertEquals(literal, annotation);
        assertNotEquals(literal, new Literals.FallbackLiteral(Fallback.DEFAULT.class, "other",
                array(IOException.class), array()));
        assertTrue(literal.toString().contains("fallbackMethod=recover"), literal.toString());
        assertEquals(Fallback.class, literal.annotationType());
    }

    @Test
    void theOtherLiteralsNameTheirAnnotation() {
        assertEquals(org.eclipse.microprofile.faulttolerance.Timeout.class,
                new Literals.TimeoutLiteral(1L, ChronoUnit.SECONDS).annotationType());
        assertEquals(org.eclipse.microprofile.faulttolerance.Bulkhead.class,
                new Literals.BulkheadLiteral(10, 10).annotationType());
        assertEquals(org.eclipse.microprofile.faulttolerance.Asynchronous.class,
                Literals.AsynchronousLiteral.INSTANCE.annotationType());
        assertEquals("m(int)", FaultToleranceCompanion.methodKey("m", int.class));
    }
}
