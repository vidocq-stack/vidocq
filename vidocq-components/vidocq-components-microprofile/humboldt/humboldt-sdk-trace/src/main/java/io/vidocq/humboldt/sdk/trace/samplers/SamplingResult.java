package io.vidocq.humboldt.sdk.trace.samplers;

import io.opentelemetry.api.common.Attributes;

/**
 * Décision d'un {@link Sampler} : enregistré (et exporté) / enregistré uniquement / abandonné,
 * avec optionnellement des attributs additionnels et un override de trace state.
 */
public record SamplingResult(Decision decision, Attributes attributes) {

    private static final SamplingResult DROP = new SamplingResult(Decision.DROP, Attributes.empty());
    private static final SamplingResult RECORD_ONLY = new SamplingResult(Decision.RECORD_ONLY, Attributes.empty());
    private static final SamplingResult RECORD_AND_SAMPLE = new SamplingResult(Decision.RECORD_AND_SAMPLE, Attributes.empty());

    public SamplingResult {
        if (decision == null) throw new NullPointerException("decision");
        if (attributes == null) attributes = Attributes.empty();
    }

    public static SamplingResult drop() {
        return DROP;
    }

    public static SamplingResult recordOnly() {
        return RECORD_ONLY;
    }

    public static SamplingResult recordAndSample() {
        return RECORD_AND_SAMPLE;
    }

    public enum Decision {
        DROP,
        RECORD_ONLY,
        RECORD_AND_SAMPLE
    }
}
