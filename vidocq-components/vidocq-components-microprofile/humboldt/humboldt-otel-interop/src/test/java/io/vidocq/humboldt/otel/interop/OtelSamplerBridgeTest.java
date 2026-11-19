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

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.samplers.SamplingDecision;
import io.vidocq.humboldt.sdk.trace.data.LinkData;
import io.vidocq.humboldt.sdk.trace.samplers.SamplingResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OtelSamplerBridgeTest {

    private static final String TRACE_ID = "0123456789abcdef0123456789abcdef";
    private static final AttributeKey<String> EXTRA = AttributeKey.stringKey("extra");

    /** OTel sampler returning a fixed result and recording the links it receives. */
    private static final class FixedSampler implements io.opentelemetry.sdk.trace.samplers.Sampler {
        private final io.opentelemetry.sdk.trace.samplers.SamplingResult result;
        private final AtomicReference<List<io.opentelemetry.sdk.trace.data.LinkData>> links = new AtomicReference<>();

        FixedSampler(io.opentelemetry.sdk.trace.samplers.SamplingResult result) {
            this.result = result;
        }

        @Override
        public io.opentelemetry.sdk.trace.samplers.SamplingResult shouldSample(
                Context parentContext, String traceId, String name, SpanKind spanKind,
                Attributes attributes, List<io.opentelemetry.sdk.trace.data.LinkData> parentLinks) {
            links.set(parentLinks);
            return result;
        }

        @Override
        public String getDescription() {
            return "Fixed";
        }
    }

    private static SamplingResult sample(io.opentelemetry.sdk.trace.samplers.Sampler otel, List<LinkData> links) {
        return new OtelSamplerBridge(otel).shouldSample(Context.root(), TRACE_ID, "op",
                SpanKind.SERVER, Attributes.empty(), links);
    }

    @Test
    void maps_every_decision() {
        assertEquals(SamplingResult.Decision.DROP,
                sample(io.opentelemetry.sdk.trace.samplers.Sampler.alwaysOff(), List.of()).decision());
        assertEquals(SamplingResult.Decision.RECORD_AND_SAMPLE,
                sample(io.opentelemetry.sdk.trace.samplers.Sampler.alwaysOn(), List.of()).decision());
        assertEquals(SamplingResult.Decision.RECORD_ONLY,
                sample(new FixedSampler(io.opentelemetry.sdk.trace.samplers.SamplingResult.recordOnly()),
                        List.of()).decision());
    }

    @Test
    void keeps_the_sampler_attributes_and_converts_the_links() {
        var otelResult = io.opentelemetry.sdk.trace.samplers.SamplingResult.create(
                SamplingDecision.RECORD_AND_SAMPLE, Attributes.of(EXTRA, "yes"));
        FixedSampler sampler = new FixedSampler(otelResult);
        SpanContext linked = SpanContext.create(TRACE_ID, "fedcba9876543210",
                TraceFlags.getSampled(), TraceState.getDefault());
        Attributes linkAttrs = Attributes.of(AttributeKey.stringKey("link"), "l");

        SamplingResult result = sample(sampler, List.of(new LinkData(linked, linkAttrs)));

        assertEquals("yes", result.attributes().get(EXTRA));
        var received = sampler.links.get();
        assertEquals(1, received.size());
        assertEquals(linked, received.get(0).getSpanContext());
        assertEquals(linkAttrs, received.get(0).getAttributes());
    }

    @Test
    void description_names_the_delegate() {
        String description = new OtelSamplerBridge(new FixedSampler(
                io.opentelemetry.sdk.trace.samplers.SamplingResult.drop())).description();
        assertEquals("OtelSamplerBridge[Fixed]", description);
    }

    @Test
    void probe_returns_the_delegate_decision() {
        assertEquals(SamplingDecision.DROP,
                new OtelSamplerBridge(io.opentelemetry.sdk.trace.samplers.Sampler.alwaysOff()).probe());
        assertEquals(SamplingDecision.RECORD_AND_SAMPLE,
                new OtelSamplerBridge(io.opentelemetry.sdk.trace.samplers.Sampler.alwaysOn()).probe());
    }
}
