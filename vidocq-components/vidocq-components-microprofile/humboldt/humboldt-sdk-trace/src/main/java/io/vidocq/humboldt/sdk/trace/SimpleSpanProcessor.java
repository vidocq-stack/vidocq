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
package io.vidocq.humboldt.sdk.trace;

import io.opentelemetry.context.Context;
import io.vidocq.humboldt.sdk.common.CompletableResultCode;
import io.vidocq.humboldt.sdk.trace.export.SpanExporter;
import io.vidocq.humboldt.sdk.trace.export.SpanProcessor;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Synchronous export — each {@code onEnd()} immediately triggers
 * {@code exporter.export([spanData])}.
 *
 * <p>Suitable for reliable, fast exporters (in-memory, logging). For network
 * exporters, prefer {@link BatchSpanProcessor}.</p>
 *
 * <p>Exports only sampled spans ({@code SpanContext.isSampled() == true}).</p>
 */
public final class SimpleSpanProcessor implements SpanProcessor {

    private final SpanExporter exporter;
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    public static SimpleSpanProcessor create(SpanExporter exporter) {
        return new SimpleSpanProcessor(exporter);
    }

    private SimpleSpanProcessor(SpanExporter exporter) {
        if (exporter == null) throw new NullPointerException("exporter");
        this.exporter = exporter;
    }

    @Override
    public void onStart(Context parentContext, ReadableSpan span) {
        // no-op
    }

    @Override
    public void onEnd(ReadableSpan span) {
        if (stopped.get()) return;
        if (!span.getSpanContext().isSampled()) return;
        exporter.export(List.of(span.toSpanData()));
    }

    @Override
    public boolean isStartRequired() {
        return false;
    }

    @Override
    public boolean isEndRequired() {
        return true;
    }

    @Override
    public CompletableResultCode flush() {
        return exporter.flush();
    }

    @Override
    public CompletableResultCode shutdown() {
        if (!stopped.compareAndSet(false, true)) return CompletableResultCode.ofSuccess();
        return exporter.shutdown();
    }
}
