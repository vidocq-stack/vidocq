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
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.vidocq.humboldt.sdk.trace.data.LinkData;

import java.util.List;

/**
 * Hierarchical sampler: if the parent exists and is sampled → recordAndSample,
 * otherwise delegates to {@code rootSampler} for root spans.
 *
 * <p>Simplified variant of the OTel version — it does not configure
 * {@code remoteParentNotSampled}, etc. separately, and follows the parent's {@code SAMPLED} bit.</p>
 */
public final class ParentBasedSampler implements Sampler {

    private final Sampler rootSampler;

    ParentBasedSampler(Sampler rootSampler) {
        if (rootSampler == null) throw new NullPointerException("rootSampler");
        this.rootSampler = rootSampler;
    }

    @Override
    public SamplingResult shouldSample(
            Context parentContext,
            String traceId,
            String name,
            SpanKind kind,
            Attributes attributes,
            List<LinkData> parentLinks) {
        SpanContext parent = Span.fromContext(parentContext).getSpanContext();
        if (parent.isValid()) {
            return parent.isSampled() ? SamplingResult.recordAndSample() : SamplingResult.drop();
        }
        return rootSampler.shouldSample(parentContext, traceId, name, kind, attributes, parentLinks);
    }

    @Override
    public String description() {
        return "ParentBased(root=" + rootSampler.description() + ")";
    }
}
