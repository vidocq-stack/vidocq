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
package io.vidocq.humboldt.sdk.metric.internal;

import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.LongHistogramBuilder;
import io.vidocq.humboldt.sdk.metric.aggregation.ExplicitBucketHistogramAggregator;
import io.vidocq.humboldt.sdk.metric.data.AggregationTemporality;
import io.vidocq.humboldt.sdk.metric.data.InstrumentType;

import java.util.List;

public final class SdkLongHistogramBuilder implements LongHistogramBuilder {

    private final String name;
    private final SdkMeter meter;
    private String description;
    private String unit;
    private List<Double> bucketBoundaries = ExplicitBucketHistogramAggregator.DEFAULT_BOUNDARIES;

    SdkLongHistogramBuilder(String name, SdkMeter meter, String description, String unit) {
        this.name = name; this.meter = meter;
        this.description = description != null ? description : "";
        this.unit = unit != null ? unit : "";
    }

    @Override public LongHistogramBuilder setDescription(String d) { this.description = d != null ? d : ""; return this; }
    @Override public LongHistogramBuilder setUnit(String u) { this.unit = u != null ? u : ""; return this; }

    @Override
    public LongHistogramBuilder setExplicitBucketBoundariesAdvice(List<Long> boundaries) {
        if (boundaries != null && !boundaries.isEmpty()) {
            this.bucketBoundaries = boundaries.stream().map(Long::doubleValue).toList();
        }
        return this;
    }

    @Override
    public LongHistogram build() {
        ExplicitBucketHistogramAggregator agg = new ExplicitBucketHistogramAggregator(bucketBoundaries);
        SdkLongHistogram h = new SdkLongHistogram(agg);
        meter.register(new InstrumentEntry(name, description, unit,
                InstrumentType.HISTOGRAM, AggregationTemporality.CUMULATIVE, false, agg));
        return h;
    }
}
