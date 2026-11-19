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
package io.vidocq.humboldt.sdk.trace.samplers;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.vidocq.humboldt.sdk.trace.data.LinkData;

import java.util.List;
import java.util.Locale;

/**
 * Consistent probabilistic sampler: for a given traceId, the decision is stable
 * (two spans from the same trace will either both be sampled or both dropped).
 *
 * <p>Implementation: extracts the last 8 bytes of the hex traceId (64 bits),
 * compares them to the {@code ratio × 2^63} threshold. Aligned with the reference OTel algorithm.</p>
 *
 * @see <a href="https://github.com/open-telemetry/opentelemetry-specification/blob/main/specification/trace/tracestate-probability-sampling.md">Spec OTel</a>
 */
public final class TraceIdRatioBasedSampler implements Sampler {

    private final double ratio;
    private final long upperBound;
    private final String description;

    TraceIdRatioBasedSampler(double ratio) {
        if (ratio < 0.0 || ratio > 1.0) {
            throw new IllegalArgumentException("ratio must be in [0, 1]: " + ratio);
        }
        this.ratio = ratio;
        if (ratio == 0.0) {
            this.upperBound = Long.MIN_VALUE;
        } else if (ratio == 1.0) {
            this.upperBound = Long.MAX_VALUE;
        } else {
            this.upperBound = (long) (ratio * Long.MAX_VALUE);
        }
        this.description = String.format(Locale.ROOT, "TraceIdRatioBased{%.6f}", ratio);
    }

    @Override
    public SamplingResult shouldSample(
            Context parentContext,
            String traceId,
            String name,
            SpanKind kind,
            Attributes attributes,
            List<LinkData> parentLinks) {
        if (ratio == 0.0) return SamplingResult.drop();
        if (ratio == 1.0) return SamplingResult.recordAndSample();
        long low64 = parseLast8BytesAsLong(traceId);
        return Math.abs(low64) < upperBound
                ? SamplingResult.recordAndSample()
                : SamplingResult.drop();
    }

    @Override
    public String description() {
        return description;
    }

    private static long parseLast8BytesAsLong(String traceId) {
        // The traceId is 32 hex chars. We take the last 16 = 8 bytes.
        // Long.parseUnsignedLong tolerates the high bit → cast to signed for Math.abs.
        return Long.parseUnsignedLong(traceId.substring(16, 32), 16);
    }
}
