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
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;

import java.util.List;

/**
 * A finished span in the OTel SDK's {@link SpanData} form, as {@link SpanDataMapper} hands it to an exporter.
 * Humboldt's own type: the SDK's only public implementation, {@code TestSpanData}, lives in
 * {@code opentelemetry-sdk-testing}, a test library that then reached every packaged application.
 */
record OtelSpanData(
        String name,
        SpanKind kind,
        SpanContext spanContext,
        SpanContext parentSpanContext,
        StatusData status,
        long startEpochNanos,
        long endEpochNanos,
        boolean ended,
        Attributes attributes,
        List<EventData> events,
        List<LinkData> links,
        Resource resource,
        InstrumentationScopeInfo instrumentationScopeInfo) implements SpanData {

    OtelSpanData {
        events = List.copyOf(events);
        links = List.copyOf(links);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public SpanKind getKind() {
        return kind;
    }

    @Override
    public SpanContext getSpanContext() {
        return spanContext;
    }

    @Override
    public SpanContext getParentSpanContext() {
        return parentSpanContext;
    }

    @Override
    public StatusData getStatus() {
        return status;
    }

    @Override
    public long getStartEpochNanos() {
        return startEpochNanos;
    }

    @Override
    public Attributes getAttributes() {
        return attributes;
    }

    @Override
    public List<EventData> getEvents() {
        return events;
    }

    @Override
    public List<LinkData> getLinks() {
        return links;
    }

    @Override
    public long getEndEpochNanos() {
        return endEpochNanos;
    }

    @Override
    public boolean hasEnded() {
        return ended;
    }

    @Override
    public int getTotalRecordedEvents() {
        return events.size();
    }

    @Override
    public int getTotalRecordedLinks() {
        return links.size();
    }

    @Override
    public int getTotalAttributeCount() {
        return attributes.size();
    }

    @Override
    public InstrumentationScopeInfo getInstrumentationScopeInfo() {
        return instrumentationScopeInfo;
    }

    /**
     * Still abstract in {@link SpanData}: the scope, under its former name.
     *
     * @deprecated as {@link SpanData#getInstrumentationLibraryInfo()} is; use {@link #getInstrumentationScopeInfo()}.
     *             It stays as long as the interface declares it abstract.
     */
    @Override
    @Deprecated(since = "OpenTelemetry SDK 1.14")
    @SuppressWarnings("deprecation")
    public io.opentelemetry.sdk.common.InstrumentationLibraryInfo getInstrumentationLibraryInfo() {
        return io.opentelemetry.sdk.common.InstrumentationLibraryInfo.create(
                instrumentationScopeInfo.getName(), instrumentationScopeInfo.getVersion(),
                instrumentationScopeInfo.getSchemaUrl());
    }

    @Override
    public Resource getResource() {
        return resource;
    }
}
