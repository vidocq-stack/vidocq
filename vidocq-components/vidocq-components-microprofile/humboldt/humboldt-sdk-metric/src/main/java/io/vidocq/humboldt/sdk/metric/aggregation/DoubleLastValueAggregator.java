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
package io.vidocq.humboldt.sdk.metric.aggregation;

import io.opentelemetry.api.common.Attributes;
import io.vidocq.humboldt.sdk.metric.data.DoublePointData;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * LastValue aggregation for DoubleGauge — keeps the latest double value
 * recorded for each attribute set.
 */
public final class DoubleLastValueAggregator implements Aggregator<DoublePointData> {

    private final ConcurrentHashMap<Attributes, AtomicReference<Double>> values = new ConcurrentHashMap<>();

    @Override
    public void recordDouble(double value, Attributes attributes) {
        Attributes key = attributes != null ? attributes : Attributes.empty();
        values.computeIfAbsent(key, k -> new AtomicReference<>(0.0)).set(value);
    }

    @Override
    public List<DoublePointData> collect(long startEpochNanos, long epochNanos) {
        List<DoublePointData> out = new ArrayList<>(values.size());
        values.forEach((attrs, holder) ->
                out.add(new DoublePointData(startEpochNanos, epochNanos, attrs, holder.get())));
        return List.copyOf(out);
    }
}
