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

import java.lang.annotation.Annotation;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Objects;
import org.eclipse.microprofile.faulttolerance.Asynchronous;
import org.eclipse.microprofile.faulttolerance.Bulkhead;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.FallbackHandler;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;

/**
 * Instances of the Fault Tolerance annotations a generated {@link FaultToleranceCompanion} builds with constant
 * values, instead of reading them by reflection. Each is a record whose components are the annotation's members, so
 * its accessors implement them.
 *
 * <p>Heisenberg only reads their members. Those with array members compare and print them by content, as an
 * annotation does.</p>
 */
public final class Literals {

    private Literals() {
    }

    /** Equality of two member values, arrays by content, as {@link Annotation#equals} compares them. */
    private static boolean same(Object left, Object right) {
        return left instanceof Object[] a && right instanceof Object[] b ? Arrays.equals(a, b) : Objects.equals(left, right);
    }

    /** A hash of member values, arrays by content. */
    private static int hash(Object... members) {
        return Arrays.deepHashCode(members);
    }

    /** {@code @Type(member=value, ...)}, arrays by content. */
    private static String describe(Class<? extends Annotation> type, Object... namesAndValues) {
        StringBuilder out = new StringBuilder("@").append(type.getName()).append('(');
        for (int i = 0; i < namesAndValues.length; i += 2) {
            if (i > 0) out.append(", ");
            Object value = namesAndValues[i + 1];
            out.append(namesAndValues[i]).append('=')
                    .append(value instanceof Object[] array ? Arrays.toString(array) : String.valueOf(value));
        }
        return out.append(')').toString();
    }

    /** A {@link Retry}. */
    public record RetryLiteral(int maxRetries, long delay, ChronoUnit delayUnit, long maxDuration,
                               ChronoUnit durationUnit, long jitter, ChronoUnit jitterDelayUnit,
                               Class<? extends Throwable>[] retryOn, Class<? extends Throwable>[] abortOn)
            implements Retry {
        @Override
        public Class<? extends Annotation> annotationType() {
            return Retry.class;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Retry r && maxRetries == r.maxRetries() && delay == r.delay()
                    && delayUnit == r.delayUnit() && maxDuration == r.maxDuration() && durationUnit == r.durationUnit()
                    && jitter == r.jitter() && jitterDelayUnit == r.jitterDelayUnit()
                    && same(retryOn, r.retryOn()) && same(abortOn, r.abortOn());
        }

        @Override
        public int hashCode() {
            return hash(maxRetries, delay, delayUnit, maxDuration, durationUnit, jitter, jitterDelayUnit, retryOn,
                    abortOn);
        }

        @Override
        public String toString() {
            return describe(Retry.class, "maxRetries", maxRetries, "delay", delay, "delayUnit", delayUnit,
                    "maxDuration", maxDuration, "durationUnit", durationUnit, "jitter", jitter,
                    "jitterDelayUnit", jitterDelayUnit, "retryOn", retryOn, "abortOn", abortOn);
        }
    }

    /** A {@link Timeout}. */
    public record TimeoutLiteral(long value, ChronoUnit unit) implements Timeout {
        @Override
        public Class<? extends Annotation> annotationType() {
            return Timeout.class;
        }
    }

    /** A {@link CircuitBreaker}. */
    public record CircuitBreakerLiteral(Class<? extends Throwable>[] failOn, Class<? extends Throwable>[] skipOn,
                                        long delay, ChronoUnit delayUnit, int requestVolumeThreshold,
                                        double failureRatio, int successThreshold) implements CircuitBreaker {
        @Override
        public Class<? extends Annotation> annotationType() {
            return CircuitBreaker.class;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof CircuitBreaker c && same(failOn, c.failOn()) && same(skipOn, c.skipOn())
                    && delay == c.delay() && delayUnit == c.delayUnit()
                    && requestVolumeThreshold == c.requestVolumeThreshold()
                    && Double.compare(failureRatio, c.failureRatio()) == 0 && successThreshold == c.successThreshold();
        }

        @Override
        public int hashCode() {
            return hash(failOn, skipOn, delay, delayUnit, requestVolumeThreshold, failureRatio, successThreshold);
        }

        @Override
        public String toString() {
            return describe(CircuitBreaker.class, "failOn", failOn, "skipOn", skipOn, "delay", delay,
                    "delayUnit", delayUnit, "requestVolumeThreshold", requestVolumeThreshold,
                    "failureRatio", failureRatio, "successThreshold", successThreshold);
        }
    }

    /** A {@link Bulkhead}. */
    public record BulkheadLiteral(int value, int waitingTaskQueue) implements Bulkhead {
        @Override
        public Class<? extends Annotation> annotationType() {
            return Bulkhead.class;
        }
    }

    /** A {@link Fallback}. */
    public record FallbackLiteral(Class<? extends FallbackHandler<?>> value, String fallbackMethod,
                                  Class<? extends Throwable>[] applyOn, Class<? extends Throwable>[] skipOn)
            implements Fallback {
        @Override
        public Class<? extends Annotation> annotationType() {
            return Fallback.class;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Fallback f && Objects.equals(value, f.value())
                    && Objects.equals(fallbackMethod, f.fallbackMethod())
                    && same(applyOn, f.applyOn()) && same(skipOn, f.skipOn());
        }

        @Override
        public int hashCode() {
            return hash(value, fallbackMethod, applyOn, skipOn);
        }

        @Override
        public String toString() {
            return describe(Fallback.class, "value", value, "fallbackMethod", fallbackMethod, "applyOn", applyOn,
                    "skipOn", skipOn);
        }
    }

    /** An {@link Asynchronous}. */
    public record AsynchronousLiteral() implements Asynchronous {
        /** The one instance a companion needs. */
        public static final AsynchronousLiteral INSTANCE = new AsynchronousLiteral();

        @Override
        public Class<? extends Annotation> annotationType() {
            return Asynchronous.class;
        }
    }
}
