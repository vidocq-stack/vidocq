/*
 * Copyright (c) 2026 Vidocq contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package io.vidocq.humboldt.tck.arquillian;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.samplers.SamplingDecision;
import io.vidocq.humboldt.sdk.trace.data.LinkData;
import io.vidocq.humboldt.sdk.trace.samplers.Sampler;
import io.vidocq.humboldt.sdk.trace.samplers.SamplingResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Adapts an {@link io.opentelemetry.sdk.trace.samplers.Sampler OTel Sampler} into
 * a {@link Sampler humboldt Sampler}. This allows Arquillian harnesses (humboldt-tck)
 * to use a custom sampler provided through the OTel SPI
 * {@code ConfigurableSamplerProvider} in the WAR without re-implementing Humboldt-side
 * decision logic.
 *
 * <p>The {@code Sampler}, {@code SamplingResult.Decision}, and {@code LinkData} types
 * are structurally equivalent between the OTel SDK and humboldt-sdk-trace; the bridge
 * is therefore a 1:1 delegation with trivial enum mapping.</p>
 */
final class OtelSamplerBridge implements Sampler {

    private final io.opentelemetry.sdk.trace.samplers.Sampler delegate;

    OtelSamplerBridge(io.opentelemetry.sdk.trace.samplers.Sampler delegate) {
        this.delegate = delegate;
    }

    @Override
    public SamplingResult shouldSample(Context parentContext, String traceId, String name,
                                        SpanKind kind, Attributes attributes,
                                        List<LinkData> parentLinks) {
        List<io.opentelemetry.sdk.trace.data.LinkData> otelLinks = new ArrayList<>(parentLinks.size());
        for (LinkData l : parentLinks) {
            otelLinks.add(io.opentelemetry.sdk.trace.data.LinkData.create(l.spanContext(), l.attributes()));
        }
        var otelResult = delegate.shouldSample(parentContext, traceId, name, kind, attributes, otelLinks);
        SamplingResult.Decision decision = switch (otelResult.getDecision()) {
            case DROP -> SamplingResult.Decision.DROP;
            case RECORD_ONLY -> SamplingResult.Decision.RECORD_ONLY;
            case RECORD_AND_SAMPLE -> SamplingResult.Decision.RECORD_AND_SAMPLE;
        };
        Attributes extraAttrs = otelResult.getAttributes();
        return new SamplingResult(decision, extraAttrs == null ? Attributes.empty() : extraAttrs);
    }

    @Override
    public String description() {
        return "OtelSamplerBridge[" + delegate.getDescription() + "]";
    }

    /** Helper for checking the type (DROP/RECORD/etc.) during the TCK probe. */
    SamplingDecision probe() {
        return delegate.shouldSample(Context.root(),
                io.opentelemetry.api.trace.TraceId.fromLongs(0L, 1L),
                "probe", SpanKind.INTERNAL, Attributes.empty(),
                java.util.Collections.emptyList()).getDecision();
    }
}
