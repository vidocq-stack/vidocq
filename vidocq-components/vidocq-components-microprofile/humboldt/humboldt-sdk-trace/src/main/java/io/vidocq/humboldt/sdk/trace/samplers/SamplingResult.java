package io.vidocq.humboldt.sdk.trace.samplers;

import io.opentelemetry.api.common.Attributes;

/**
 * Decision from a {@link Sampler}: recorded (and exported) / recorded only / dropped,
 * optionally with additional attributes and a trace-state override.
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
