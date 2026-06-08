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

/**
 * Decides for each new span whether it is sampled (recorded + exported),
 * recorded only (not exported), or dropped.
 */
public interface Sampler {

    SamplingResult shouldSample(
            Context parentContext,
            String traceId,
            String name,
            SpanKind kind,
            Attributes attributes,
            List<LinkData> parentLinks);

    String description();

    static Sampler alwaysOn() {
        return AlwaysOnSampler.INSTANCE;
    }

    static Sampler alwaysOff() {
        return AlwaysOffSampler.INSTANCE;
    }

    static Sampler parentBased(Sampler rootSampler) {
        return new ParentBasedSampler(rootSampler);
    }

    static Sampler traceIdRatioBased(double ratio) {
        return new TraceIdRatioBasedSampler(ratio);
    }
}
