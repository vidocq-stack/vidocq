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
package io.vidocq.humboldt.otel.interop;

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
 * a {@link Sampler Humboldt Sampler}. This allows harnesses to use a custom sampler
 * provided through the OTel {@code ConfigurableSamplerProvider} SPI without
 * re-implementing Humboldt-side decision logic.
 *
 * <p>The {@code Sampler}, {@code SamplingResult.Decision}, and {@code LinkData} types
 * are structurally equivalent between the OTel SDK and humboldt-sdk-trace; the bridge
 * is therefore a 1:1 delegation with trivial enum mapping.</p>
 */
public final class OtelSamplerBridge implements Sampler {

    private final io.opentelemetry.sdk.trace.samplers.Sampler delegate;

    public OtelSamplerBridge(io.opentelemetry.sdk.trace.samplers.Sampler delegate) {
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

    /** Helper probing the delegate's decision type (DROP/RECORD/...) on a synthetic span. */
    SamplingDecision probe() {
        return delegate.shouldSample(Context.root(),
                io.opentelemetry.api.trace.TraceId.fromLongs(0L, 1L),
                "probe", SpanKind.INTERNAL, Attributes.empty(),
                java.util.Collections.emptyList()).getDecision();
    }
}
