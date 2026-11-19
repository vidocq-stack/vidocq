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
/**
 * Humboldt SDK Metric — OpenTelemetry implementation of the {@code metrics} signal.
 *
 * <p>M4 MVP: synchronous instruments (LongCounter, DoubleHistogram),
 * aggregations CUMULATIVE (Sum, ExplicitBucketHistogram), PeriodicMetricReader
 * on a virtual thread, MetricExporter SPI.</p>
 *
 * <p>Deferred to M4b: asynchronous instruments (Observable*), missing
 * Long/Double variants, ExponentialHistogram, ViewRegistry / advice,
 * DELTA temporality.</p>
 */
module io.vidocq.humboldt.sdk.metric {

    requires transitive io.vidocq.humboldt.api;
    requires transitive io.vidocq.humboldt.sdk.common;
    requires transitive io.opentelemetry.api;
    requires java.logging;

    exports io.vidocq.humboldt.sdk.metric;
    exports io.vidocq.humboldt.sdk.metric.data;
    exports io.vidocq.humboldt.sdk.metric.aggregation;
    exports io.vidocq.humboldt.sdk.metric.export;
}
