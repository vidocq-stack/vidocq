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
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.context.Context;
import io.vidocq.humboldt.sdk.metric.aggregation.ExplicitBucketHistogramAggregator;

/**
 * Internal implementation of {@link DoubleHistogram} — delegates to
 * {@link ExplicitBucketHistogramAggregator}.
 *
 * <p>Rejects negative values (OTel histogram convention for durations/sizes).</p>
 */
public final class SdkDoubleHistogram implements DoubleHistogram {

    private final ExplicitBucketHistogramAggregator aggregator;

    SdkDoubleHistogram(ExplicitBucketHistogramAggregator aggregator) {
        this.aggregator = aggregator;
    }

    @Override
    public void record(double value) {
        record(value, Attributes.empty());
    }

    @Override
    public void record(double value, Attributes attributes) {
        if (value < 0.0 || Double.isNaN(value)) return;
        aggregator.recordDouble(value, attributes != null ? attributes : Attributes.empty());
    }

    @Override
    public void record(double value, Attributes attributes, Context context) {
        record(value, attributes);
    }
}
