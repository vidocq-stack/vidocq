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

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.context.Context;
import io.vidocq.humboldt.sdk.metric.aggregation.ExplicitBucketHistogramAggregator;

/** Long-typed histogram — delegates to ExplicitBucketHistogramAggregator (long→double cast). */
public final class SdkLongHistogram implements LongHistogram {

    private final ExplicitBucketHistogramAggregator aggregator;

    SdkLongHistogram(ExplicitBucketHistogramAggregator aggregator) { this.aggregator = aggregator; }

    @Override public void record(long value) { record(value, Attributes.empty()); }

    @Override
    public void record(long value, Attributes attributes) {
        if (value < 0L) return; // OTel alignment: reject negatives for histograms
        aggregator.recordDouble((double) value, attributes != null ? attributes : Attributes.empty());
    }

    @Override
    public void record(long value, Attributes attributes, Context context) { record(value, attributes); }
}
