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
package io.vidocq.humboldt.sdk.metric.export;

import io.vidocq.humboldt.sdk.common.CompletableResultCode;

/**
 * Metrics reader — pull-based or push-based. Connected to an
 * {@link io.vidocq.humboldt.sdk.metric.SdkMeterProvider} via {@code register()}.
 *
 * <p>Standard implementation: {@code PeriodicMetricReader}.</p>
 */
public interface MetricReader extends AutoCloseable {

    /** The provider calls this once so the reader can invoke {@code provider.collectMetrics()}. */
    void register(CollectionRegistration registration);

    /** Forces immediate collection + export. */
    CompletableResultCode flush();

    CompletableResultCode shutdown();

    @Override
    default void close() {
        shutdown().join(10, java.util.concurrent.TimeUnit.SECONDS);
    }

    /** Callback provided by SdkMeterProvider to the reader (to trigger collection). */
    @FunctionalInterface
    interface CollectionRegistration {
        java.util.Collection<io.vidocq.humboldt.sdk.metric.data.MetricData> collectAllMetrics();
    }
}
