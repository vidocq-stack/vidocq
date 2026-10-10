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
package io.vidocq.heisenberg.cdi.moduleit;

import io.vidocq.heisenberg.cdi.internal.BulkheadStateRegistryBean;
import io.vidocq.heisenberg.cdi.internal.DiracFtMetricsRecorder;
import io.vidocq.heisenberg.cdi.internal.FaultToleranceInterceptor;
import io.vidocq.heisenberg.cdi.internal.HeisenbergExtension;
import io.vidocq.heisenberg.cdi.internal.OtelFtMetricsRecorder;
import io.vidocq.heisenberg.cdi.internal.StateRegistryBean;
import io.vidocq.vauban.core.container.VaubanContainer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BUG-005: Fault Tolerance must work when neither MicroProfile Metrics nor OpenTelemetry is
 * present. Both recorder beans are always part of heisenberg-cdi-vauban, so a container that
 * discovers the archive builds them whether or not their APIs are there. This module's path has
 * neither API ({@code provided} in heisenberg-cdi-vauban), as in an application that adds Fault
 * Tolerance alone.
 */
class MetricsRecordersWithoutObservabilityTest {

    @Test
    void retries_with_both_recorders_and_no_observability_api() {
        assertTrue(ModuleLayer.boot().findModule("microprofile.metrics.api").isEmpty(),
                "MicroProfile Metrics must be absent for this test to mean anything");
        assertTrue(ModuleLayer.boot().findModule("io.opentelemetry.api").isEmpty(),
                "OpenTelemetry must be absent for this test to mean anything");

        FaultToleranceService.CALLS.set(0);
        VaubanContainer container = VaubanContainer.builder()
                .addBeanClass(HeisenbergExtension.class)
                .addBeanClass(FaultToleranceInterceptor.class)
                .addBeanClass(StateRegistryBean.class)
                .addBeanClass(BulkheadStateRegistryBean.class)
                .addBeanClass(DiracFtMetricsRecorder.class)
                .addBeanClass(OtelFtMetricsRecorder.class)
                .addBeanClass(FaultToleranceService.class)
                .build();
        try {
            FaultToleranceService svc = container.select(FaultToleranceService.class);
            try {
                svc.call();
            } catch (RuntimeException expected) {
                // Expected: the retries are exhausted and the last failure propagates.
            }
            assertEquals(4, FaultToleranceService.CALLS.get(),
                    "@Retry(maxRetries = 3) must drive 4 invocations with the recorders deployed");
        } finally {
            container.close();
        }
    }
}
