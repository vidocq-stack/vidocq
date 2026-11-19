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

import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.vidocq.humboldt.sdk.common.CompletableResultCode;

import java.util.Collection;

/**
 * Adapts an {@link SpanExporter OTel SDK SpanExporter} (for example the one provided
 * by {@code org.eclipse.microprofile.telemetry.tracing.tck.exporter.InMemorySpanExporter})
 * into an {@link io.vidocq.humboldt.sdk.trace.export.SpanExporter Humboldt SpanExporter}
 * compatible with the Humboldt trace pipeline.
 *
 * <p>For each batch received from Humboldt, converts
 * {@link io.vidocq.humboldt.sdk.trace.data.SpanData} into
 * {@link io.opentelemetry.sdk.trace.data.SpanData} via {@link SpanDataMapper}
 * and then delegates to the underlying OTel exporter.</p>
 *
 * <p>Humboldt {@link CompletableResultCode} and OTel
 * {@link io.opentelemetry.sdk.common.CompletableResultCode CompletableResultCode}
 * are semantically equivalent — the conversion is performed by
 * {@link #toHumboldt(io.opentelemetry.sdk.common.CompletableResultCode)}.</p>
 */
public final class OtelSpanExporterBridge implements io.vidocq.humboldt.sdk.trace.export.SpanExporter {

    private final SpanExporter delegate;

    public OtelSpanExporterBridge(SpanExporter delegate) {
        if (delegate == null) throw new NullPointerException("delegate");
        this.delegate = delegate;
    }

    @Override
    public CompletableResultCode export(Collection<io.vidocq.humboldt.sdk.trace.data.SpanData> spans) {
        var mapped = spans.stream().map(SpanDataMapper::toOtel).toList();
        return toHumboldt(delegate.export(mapped));
    }

    @Override
    public CompletableResultCode flush() {
        return toHumboldt(delegate.flush());
    }

    @Override
    public CompletableResultCode shutdown() {
        return toHumboldt(delegate.shutdown());
    }

    private static CompletableResultCode toHumboldt(io.opentelemetry.sdk.common.CompletableResultCode otel) {
        CompletableResultCode out = new CompletableResultCode();
        otel.whenComplete(() -> {
            if (otel.isSuccess()) out.succeed();
            else out.fail();
        });
        return out;
    }
}
