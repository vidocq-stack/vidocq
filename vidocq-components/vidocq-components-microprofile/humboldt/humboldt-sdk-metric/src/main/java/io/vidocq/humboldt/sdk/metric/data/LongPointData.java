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

/**
 * Data point for a long-typed Sum / Counter.
 *
 * @param startEpochNanos start timestamp of the cumulative window
 * @param epochNanos      collection timestamp
 * @param attributes      point labels
 * @param value           value (cumulative if CUMULATIVE)
 */
public record LongPointData(
        long startEpochNanos,
        long epochNanos,
        Attributes attributes,
        long value) implements PointData {

    public LongPointData {
        if (attributes == null) attributes = Attributes.empty();
    }
}
