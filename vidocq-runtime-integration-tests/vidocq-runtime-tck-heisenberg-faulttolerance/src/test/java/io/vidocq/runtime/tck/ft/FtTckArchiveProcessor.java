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
package io.vidocq.runtime.tck.ft;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import org.eclipse.microprofile.fault.tolerance.tck.telemetryMetrics.util.InMemoryMetricReader;
import org.jboss.arquillian.container.test.spi.client.deployment.ApplicationArchiveProcessor;
import org.jboss.arquillian.test.spi.TestClass;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.container.ClassContainer;

/**
 * Per-deployment FT TCK glue, the same way the TCK's own extension appends its
 * Awaitility/Hamcrest utilities:
 * <ul>
 *   <li>adds {@link FtMetricsRegistryProxyProducer} to the archive;</li>
 *   <li>resets the global OpenTelemetry and registers a fresh
 *       {@link InMemoryMetricReader} BEFORE the container boots, so the
 *       telemetryMetrics assertions of each deployment start from empty
 *       histograms and heisenberg's recorder captures the fresh meter at bean
 *       creation. TCK-mandated test infrastructure, not runtime wiring.</li>
 * </ul>
 * Archive processing happens on the client side right before
 * {@code DeployableContainer.deploy(...)}, which gives the required ordering.
 */
public class FtTckArchiveProcessor implements ApplicationArchiveProcessor {

    @Override
    public void process(Archive<?> archive, TestClass testClass) {
        if (archive instanceof ClassContainer<?> container) {
            container.addClass(FtMetricsRegistryProxyProducer.class);
        }
        resetGlobalTelemetry();
    }

    private static void resetGlobalTelemetry() {
        GlobalOpenTelemetry.resetForTest();
        InMemoryMetricReader reader = InMemoryMetricReader.current();
        SdkMeterProvider meterProvider = SdkMeterProvider.builder()
                .registerMetricReader(reader)
                .build();
        OpenTelemetrySdk.builder()
                .setMeterProvider(meterProvider)
                .buildAndRegisterGlobal();
    }
}
