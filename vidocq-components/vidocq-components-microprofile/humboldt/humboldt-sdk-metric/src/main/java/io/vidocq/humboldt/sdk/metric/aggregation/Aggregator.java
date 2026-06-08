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
import io.vidocq.humboldt.sdk.metric.data.PointData;

import java.util.List;

/**
 * Per-instrument storage: receives hot-path recordings
 * ({@link #recordLong(long, Attributes)} / {@link #recordDouble(double, Attributes)})
 * and produces a snapshot at collection time ({@link #collect(long, long)}).
 *
 * <p>CUMULATIVE implementations — accumulated state persists across collections,
 * and the exported value is cumulative since startup.</p>
 *
 * @param <P> produced point type (LongPointData for Sum, HistogramPointData for Histogram, ...)
 */
public interface Aggregator<P extends PointData> {

    /** Records a long value with its associated attributes. */
    default void recordLong(long value, Attributes attributes) {
        recordDouble((double) value, attributes);
    }

    /** Records a double value with its associated attributes. */
    default void recordDouble(double value, Attributes attributes) {
        recordLong((long) value, attributes);
    }

    /**
     * Produces a snapshot of the points accumulated since startup.
     *
     * @param startEpochNanos start time of the SdkMeterProvider (fixed for CUMULATIVE)
     * @param epochNanos      timestamp of the current collection
     * @return immutable list of points per attribute set
     */
    List<P> collect(long startEpochNanos, long epochNanos);
}
