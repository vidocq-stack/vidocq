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
package io.vidocq.humboldt.sdk.metric.data;

import io.vidocq.humboldt.sdk.common.Resource;
import io.vidocq.humboldt.sdk.common.InstrumentationScope;

import java.util.List;

/**
 * Collected metric (snapshot ready to export) — immutable view produced by
 * {@code MetricReader.collect()} and consumed by {@code MetricExporter.export()}.
 *
 * <p>For M4 MVP, only Sum (Counter) and Histogram are supported (the
 * {@code points} field contains {@link LongPointData} or
 * {@link HistogramPointData}). The type is carried by {@link #instrumentType()}.</p>
 */
public record MetricData(
        Resource resource,
        InstrumentationScope scope,
        String name,
        String description,
        String unit,
        InstrumentType instrumentType,
        AggregationTemporality temporality,
        boolean monotonic,
        List<? extends PointData> points) {

    public MetricData {
        if (name == null) throw new NullPointerException("name");
        if (description == null) description = "";
        if (unit == null) unit = "";
        if (instrumentType == null) throw new NullPointerException("instrumentType");
        if (temporality == null) temporality = AggregationTemporality.CUMULATIVE;
        points = points == null ? List.of() : List.copyOf(points);
        if (resource == null) resource = Resource.empty();
        if (scope == null) scope = InstrumentationScope.of("");
    }
}
