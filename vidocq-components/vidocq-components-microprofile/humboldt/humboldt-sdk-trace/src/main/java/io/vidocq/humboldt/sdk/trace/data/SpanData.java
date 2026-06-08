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
package io.vidocq.humboldt.sdk.trace.data;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.vidocq.humboldt.sdk.common.InstrumentationScope;
import io.vidocq.humboldt.sdk.common.Resource;

import java.util.List;

/**
 * Immutable view of a completed span, consumed by {@code SpanExporter}.
 *
 * <p>Frozen picture of the span state at {@code end()} time — neither the SDK nor
 * an exporter should mutate its contents after creation.</p>
 */
public record SpanData(
        SpanContext spanContext,
        SpanContext parentSpanContext,
        String name,
        SpanKind kind,
        long startEpochNanos,
        long endEpochNanos,
        Attributes attributes,
        List<EventData> events,
        List<LinkData> links,
        StatusData status,
        Resource resource,
        InstrumentationScope instrumentationScope) {

    public SpanData {
        if (spanContext == null) throw new NullPointerException("spanContext");
        if (name == null) throw new NullPointerException("name");
        if (kind == null) kind = SpanKind.INTERNAL;
        if (attributes == null) attributes = Attributes.empty();
        events = events == null ? List.of() : List.copyOf(events);
        links = links == null ? List.of() : List.copyOf(links);
        if (status == null) status = StatusData.unset();
        if (resource == null) resource = Resource.empty();
        if (instrumentationScope == null) instrumentationScope = InstrumentationScope.of("");
    }

    public boolean hasEnded() {
        return endEpochNanos > 0L;
    }
}
