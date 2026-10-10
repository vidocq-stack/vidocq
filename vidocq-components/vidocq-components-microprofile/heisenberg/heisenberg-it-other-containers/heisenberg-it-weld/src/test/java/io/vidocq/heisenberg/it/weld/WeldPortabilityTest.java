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
package io.vidocq.heisenberg.it.weld;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.faulttolerance.exceptions.BulkheadException;
import org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException;
import org.eclipse.microprofile.faulttolerance.exceptions.TimeoutException;
import org.jboss.weld.environment.se.Weld;
import org.jboss.weld.environment.se.WeldContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Heisenberg jars, unchanged, under Weld SE on a class path (vidocq-workspace#15, heisenberg#25):
 * the build compatible extension binds the interceptor to every Fault Tolerance annotation, the
 * policies run, MicroProfile Config overrides apply, and metrics are named after the bean class
 * the application wrote. Nothing generated for Vauban is used.
 */
class WeldPortabilityTest {

    private static WeldContainer container;

    @BeforeAll
    static void start() {
        // Default discovery: every bean archive of the class path, as an application would boot.
        container = new Weld().initialize();
    }

    @AfterAll
    static void stop() {
        if (container != null) {
            container.close();
        }
    }

    @Test
    void vaubanIsNotOnTheClassPath() {
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("io.vidocq.vauban.core.container.VaubanContainer"));
    }

    @Test
    void retryRetriesUntilSuccess() {
        assertEquals("ok after 3", container.select(RetryService.class).get().flaky());
    }

    @Test
    void configOverrideKeyedByTheBeanClassApplies() {
        // MP FT 4.1 §12: <class>/<method>/Retry/maxRetries. The class is the application's, so the
        // key must not depend on the container's subclass name: 1 call + 1 retry, not 1 + 5.
        RetryService service = container.select(RetryService.class).get();
        assertThrows(IllegalStateException.class, service::configured);
        assertEquals(2, service.configuredCalls());
    }

    @Test
    void timeoutInterruptsASlowCall() {
        TimeoutService service = container.select(TimeoutService.class).get();
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            assertThrows(TimeoutException.class, service::slow);
        });
    }

    @Test
    void circuitBreakerOpensAfterTheThreshold() {
        CircuitBreakerService service = container.select(CircuitBreakerService.class).get();
        assertThrows(IllegalStateException.class, service::failing);
        assertThrows(IllegalStateException.class, service::failing);
        assertThrows(CircuitBreakerOpenException.class, service::failing);
    }

    @Test
    void fallbackMethodAnswersForAFailingCall() {
        assertEquals("fallback for weld", container.select(FallbackService.class).get().primary("weld"));
    }

    @Test
    void bulkheadRejectsACallBeyondItsCapacity() throws Exception {
        BulkheadService service = container.select(BulkheadService.class).get();
        CompletableFuture<String> first = CompletableFuture.supplyAsync(() -> {
            try {
                return service.guarded();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });
        try {
            assertTrue(service.awaitEntered(), "first call never entered the bulkhead");
            assertThrows(BulkheadException.class, service::guarded);
        } finally {
            service.release();
        }
        assertEquals("done", first.get(5, TimeUnit.SECONDS));
    }

    @Test
    void metricsAreNamedAfterTheBeanClass() {
        container.select(RetryService.class).get().flaky();
        container.select(FallbackService.class).get().primary("metrics");

        var recorded = container.select(RecordingFtMetricsRecorder.class).get().beanClasses();
        assertTrue(recorded.contains(RetryService.class.getName()), recorded.toString());
        assertTrue(recorded.contains(FallbackService.class.getName()), recorded.toString());
        assertTrue(recorded.stream().noneMatch(name -> name.contains("$")),
                "no container-generated subclass may name a metric: " + recorded);
    }
}
