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
 * <p>Heisenberg only reads their members. They do not follow the {@link Annotation#equals} contract for array
 * members, which a record compares by reference.</p>
 */
public final class Literals {

    private Literals() {
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
