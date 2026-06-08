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
package io.vidocq.humboldt.sdk.trace.internal;

import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.Tracer;
import io.vidocq.humboldt.sdk.common.Clock;
import io.vidocq.humboldt.sdk.common.IdGenerator;
import io.vidocq.humboldt.sdk.common.Resource;
import io.vidocq.humboldt.sdk.common.InstrumentationScope;
import io.vidocq.humboldt.sdk.trace.export.SpanProcessor;
import io.vidocq.humboldt.sdk.trace.samplers.Sampler;

import java.util.List;

/**
 * Humboldt tracer — internal facade between the public
 * {@link io.opentelemetry.api.trace.Tracer} API and the SDK pipeline
 * (sampler + processors + exporter).
 */
public final class SdkTracer implements Tracer {

    private final InstrumentationScope scope;
    private final Resource resource;
    private final Sampler sampler;
    private final IdGenerator idGenerator;
    private final Clock clock;
    private final List<SpanProcessor> processors;

    public SdkTracer(
            InstrumentationScope scope,
            Resource resource,
            Sampler sampler,
            IdGenerator idGenerator,
            Clock clock,
            List<SpanProcessor> processors) {
        this.scope = scope;
        this.resource = resource;
        this.sampler = sampler;
        this.idGenerator = idGenerator;
        this.clock = clock;
        this.processors = processors;
    }

    @Override
    public SpanBuilder spanBuilder(String spanName) {
        String safeName = (spanName == null || spanName.isEmpty()) ? "<unnamed>" : spanName;
        return new SdkSpanBuilder(
                safeName, scope, resource, sampler, idGenerator, clock, processors);
    }
}
