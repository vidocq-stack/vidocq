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
package io.vidocq.heisenberg.cdi.internal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import org.eclipse.microprofile.faulttolerance.Asynchronous;
import org.eclipse.microprofile.faulttolerance.Bulkhead;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.eclipse.microprofile.faulttolerance.exceptions.FaultToleranceDefinitionException;
import org.junit.jupiter.api.Test;

class HeisenbergExtensionTest {

    private final HeisenbergExtension extension = new HeisenbergExtension();

    @Test
    void validatesCorrectFallbackMethodDefinition() {
        assertDoesNotThrow(() -> extension.validateClass(ValidFallbackBean.class));
    }

    @Test
    void rejectsInvalidFallbackMethodDefinition() {
        assertThrows(
                FaultToleranceDefinitionException.class,
                () -> extension.validateClass(InvalidFallbackBean.class)
        );
    }

    @Test
    void allowsAbstractBeanWhenFallbackMethodIsProvidedByConcreteSubtype() {
        assertDoesNotThrow(() -> extension.validateClass(AbstractFallbackBase.class));
        assertDoesNotThrow(() -> extension.validateClass(AbstractFallbackConcrete.class));
    }

    @Test
    void rejectsInvalidAsynchronousReturnType() {
        assertThrows(
                FaultToleranceDefinitionException.class,
                () -> extension.validateClass(InvalidAsyncBean.class)
        );
    }

    @Test
    void acceptsCompletionStageForAsynchronous() {
        assertDoesNotThrow(() -> extension.validateClass(ValidAsyncBean.class));
    }

    @Test
    void rejectsInvalidBulkheadValue() {
        assertThrows(FaultToleranceDefinitionException.class, () -> extension.validateClass(InvalidBulkheadValueBean.class));
    }

    @Test
    void rejectsInvalidBulkheadAsynchronousQueue() {
        assertThrows(FaultToleranceDefinitionException.class, () -> extension.validateClass(InvalidBulkheadQueueBean.class));
    }

    @Test
    void rejectsInvalidCircuitBreakerFailureRatio() {
        assertThrows(FaultToleranceDefinitionException.class, () -> extension.validateClass(InvalidCircuitBreakerFailureRatioBean.class));
    }

    @Test
    void rejectsInvalidCircuitBreakerRequestVolumeThreshold() {
        assertThrows(FaultToleranceDefinitionException.class, () -> extension.validateClass(InvalidCircuitBreakerRequestVolumeBean.class));
    }

    @Test
    void rejectsInvalidCircuitBreakerSuccessThreshold() {
        assertThrows(FaultToleranceDefinitionException.class, () -> extension.validateClass(InvalidCircuitBreakerSuccessThresholdBean.class));
    }

    @Test
    void rejectsInvalidRetryDelay() {
        assertThrows(FaultToleranceDefinitionException.class, () -> extension.validateClass(InvalidRetryDelayBean.class));
    }

    @Test
    void rejectsInvalidRetryJitter() {
        assertThrows(FaultToleranceDefinitionException.class, () -> extension.validateClass(InvalidRetryJitterBean.class));
    }

    @Test
    void rejectsInvalidRetryMaxRetries() {
        assertThrows(FaultToleranceDefinitionException.class, () -> extension.validateClass(InvalidRetryMaxRetriesBean.class));
    }

    @Test
    void rejectsInvalidRetryDelayDurationRelationship() {
        assertThrows(FaultToleranceDefinitionException.class, () -> extension.validateClass(InvalidRetryDelayDurationBean.class));
    }

    @Test
    void rejectsInvalidTimeoutValue() {
        assertThrows(FaultToleranceDefinitionException.class, () -> extension.validateClass(InvalidTimeoutBean.class));
    }

    static class ValidFallbackBean {
        @Fallback(fallbackMethod = "recover")
        String call() {
            return "ok";
        }

        String recover() {
            return "recover";
        }
    }

    static class InvalidFallbackBean {
        @Fallback(fallbackMethod = "recover")
        String call() {
            return "ok";
        }

        int recover() {
            return 1;
        }
    }

    abstract static class AbstractFallbackBase {
        @Fallback(fallbackMethod = "fallback")
        String call(String value) {
            return "base" + value;
        }
    }

    static class AbstractFallbackConcrete extends AbstractFallbackBase {
        String fallback(String value) {
            return "fallback:" + value;
        }
    }

    static class InvalidAsyncBean {
        @Asynchronous
        String call() {
            return "invalid";
        }
    }

    static class ValidAsyncBean {
        @Asynchronous
        CompletableFuture<String> call() {
            return CompletableFuture.completedFuture("ok");
        }
    }

    static class InvalidBulkheadValueBean {
        @Bulkhead(value = 0)
        String call() {
            return "invalid";
        }
    }

    static class InvalidBulkheadQueueBean {
        @Asynchronous
        @Bulkhead(waitingTaskQueue = -1)
        Future<String> call() {
            return CompletableFuture.completedFuture("invalid");
        }
    }

    static class InvalidCircuitBreakerFailureRatioBean {
        @CircuitBreaker(failureRatio = 1.1)
        String call() {
            return "invalid";
        }
    }

    static class InvalidCircuitBreakerRequestVolumeBean {
        @CircuitBreaker(requestVolumeThreshold = 0)
        String call() {
            return "invalid";
        }
    }

    static class InvalidCircuitBreakerSuccessThresholdBean {
        @CircuitBreaker(successThreshold = 0)
        String call() {
            return "invalid";
        }
    }

    static class InvalidRetryDelayBean {
        @Retry(delay = -1)
        String call() {
            return "invalid";
        }
    }

    static class InvalidRetryJitterBean {
        @Retry(jitter = -1)
        String call() {
            return "invalid";
        }
    }

    static class InvalidRetryMaxRetriesBean {
        @Retry(maxRetries = -2)
        String call() {
            return "invalid";
        }
    }

    static class InvalidRetryDelayDurationBean {
        @Retry(delay = 1000, maxDuration = 500)
        String call() {
            return "invalid";
        }
    }

    static class InvalidTimeoutBean {
        @Timeout(-1)
        String call() {
            return "invalid";
        }
    }
}
