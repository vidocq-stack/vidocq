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

import io.opentelemetry.api.common.Attributes;

import java.util.List;

/**
 * Data point for a histogram with explicit buckets.
 *
 * @param startEpochNanos       start timestamp of the cumulative window
 * @param epochNanos            collection timestamp
 * @param attributes            point labels
 * @param sum                   cumulative sum of recorded values
 * @param count                 number of recorded values
 * @param min                   observed minimum (NaN if nothing was ever recorded)
 * @param max                   observed maximum (NaN if nothing was ever recorded)
 * @param boundaries            explicit boundaries (size n)
 * @param bucketCounts          counts per bucket (size n+1)
 */
public record HistogramPointData(
        long startEpochNanos,
        long epochNanos,
        Attributes attributes,
        double sum,
        long count,
        double min,
        double max,
        List<Double> boundaries,
        List<Long> bucketCounts) implements PointData {

    public HistogramPointData {
        if (attributes == null) attributes = Attributes.empty();
        if (boundaries == null) boundaries = List.of();
        if (bucketCounts == null) bucketCounts = List.of();
        boundaries = List.copyOf(boundaries);
        bucketCounts = List.copyOf(bucketCounts);
        if (bucketCounts.size() != boundaries.size() + 1) {
            throw new IllegalArgumentException(
                    "bucketCounts.size() must be boundaries.size()+1: "
                            + bucketCounts.size() + " vs " + boundaries.size());
        }
    }
}
