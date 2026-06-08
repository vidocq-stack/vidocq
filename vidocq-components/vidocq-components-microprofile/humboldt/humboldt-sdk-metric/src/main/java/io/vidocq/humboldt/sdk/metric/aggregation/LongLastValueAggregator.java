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
import io.vidocq.humboldt.sdk.metric.data.LongPointData;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * LastValue aggregation for LongGauge (synchronous instrument) — keeps the latest
 * value recorded for each attribute set. OTel spec: Gauge exposes the current value,
 * not a cumulative total.
 */
public final class LongLastValueAggregator implements Aggregator<LongPointData> {

    private final ConcurrentHashMap<Attributes, AtomicLong> values = new ConcurrentHashMap<>();

    @Override
    public void recordLong(long value, Attributes attributes) {
        Attributes key = attributes != null ? attributes : Attributes.empty();
        values.computeIfAbsent(key, k -> new AtomicLong()).set(value);
    }

    @Override
    public List<LongPointData> collect(long startEpochNanos, long epochNanos) {
        List<LongPointData> out = new ArrayList<>(values.size());
        values.forEach((attrs, holder) ->
                out.add(new LongPointData(startEpochNanos, epochNanos, attrs, holder.get())));
        return List.copyOf(out);
    }
}
