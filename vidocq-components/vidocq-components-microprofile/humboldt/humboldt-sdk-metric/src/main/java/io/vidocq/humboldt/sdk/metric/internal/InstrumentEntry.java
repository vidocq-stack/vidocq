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

import io.vidocq.humboldt.sdk.metric.aggregation.Aggregator;
import io.vidocq.humboldt.sdk.metric.data.AggregationTemporality;
import io.vidocq.humboldt.sdk.metric.data.InstrumentType;
import io.vidocq.humboldt.sdk.metric.data.PointData;

/**
 * Entry registered by an {@link io.vidocq.humboldt.sdk.metric.internal.SdkMeter}
 * for each created instrument — aggregates descriptor + aggregator.
 */
public record InstrumentEntry(
        String name,
        String description,
        String unit,
        InstrumentType type,
        AggregationTemporality temporality,
        boolean monotonic,
        Aggregator<? extends PointData> aggregator) {

    public InstrumentEntry {
        if (name == null) throw new NullPointerException("name");
        if (description == null) description = "";
        if (unit == null) unit = "";
        if (type == null) throw new NullPointerException("type");
        if (temporality == null) temporality = AggregationTemporality.CUMULATIVE;
        if (aggregator == null) throw new NullPointerException("aggregator");
    }
}
