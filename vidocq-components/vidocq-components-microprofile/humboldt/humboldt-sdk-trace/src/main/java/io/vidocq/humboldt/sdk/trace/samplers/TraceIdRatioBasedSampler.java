package io.vidocq.humboldt.sdk.trace.samplers;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.vidocq.humboldt.sdk.trace.data.LinkData;

import java.util.List;
import java.util.Locale;

/**
 * Sampler probabiliste consistant : pour un même traceId, la décision est stable
 * (deux spans de la même trace seront soit tous deux samplés, soit tous deux drop).
 *
 * <p>Implémentation : on extrait les 8 derniers octets du traceId hex (64 bits),
 * on les compare au seuil {@code ratio × 2^63}. Aligné sur l'algo OTel de référence.</p>
 *
 * @see <a href="https://github.com/open-telemetry/opentelemetry-specification/blob/main/specification/trace/tracestate-probability-sampling.md">Spec OTel</a>
 */
public final class TraceIdRatioBasedSampler implements Sampler {

    private final double ratio;
    private final long upperBound;
    private final String description;

    TraceIdRatioBasedSampler(double ratio) {
        if (ratio < 0.0 || ratio > 1.0) {
            throw new IllegalArgumentException("ratio doit être dans [0, 1] : " + ratio);
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
        // Le traceId fait 32 chars hex. On prend les 16 derniers = 8 octets.
        // Long.parseUnsignedLong tolère le bit haut → on cast en signed pour Math.abs.
        return Long.parseUnsignedLong(traceId.substring(16, 32), 16);
    }
}
