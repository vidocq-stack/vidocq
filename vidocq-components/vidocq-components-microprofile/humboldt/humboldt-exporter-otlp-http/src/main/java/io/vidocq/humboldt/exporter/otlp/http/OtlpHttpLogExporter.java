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
package io.vidocq.humboldt.exporter.otlp.http;

import io.vidocq.humboldt.exporter.otlp.http.internal.OtlpHttpJsonSender;
import io.vidocq.humboldt.exporter.otlp.http.internal.OtlpJsonLogEncoder;
import io.vidocq.humboldt.sdk.common.CompletableResultCode;
import io.vidocq.humboldt.sdk.log.data.LogRecordData;
import io.vidocq.humboldt.sdk.log.export.LogRecordExporter;

import java.util.Collection;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * OTLP/HTTP-JSON exporter for LogRecord — delegates transport to
 * {@link OtlpHttpJsonSender}.
 *
 * <p>Default endpoint: {@code http://localhost:4318/v1/logs}.</p>
 */
public final class OtlpHttpLogExporter implements LogRecordExporter {

    private final OtlpHttpJsonSender sender;
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    private OtlpHttpLogExporter(OtlpHttpJsonSender sender) {
        this.sender = sender;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public CompletableResultCode export(Collection<LogRecordData> records) {
        if (stopped.get()) return CompletableResultCode.ofFailure();
        if (records.isEmpty()) return CompletableResultCode.ofSuccess();
        return sender.send(OtlpJsonLogEncoder.encode(records));
    }

    @Override
    public CompletableResultCode flush() {
        return sender.flush();
    }

    @Override
    public CompletableResultCode shutdown() {
        stopped.set(true);
        return sender.flush();
    }

    /** Builder of {@link OtlpHttpLogExporter}; default endpoint {@code http://localhost:4318/v1/logs}. */
    public static final class Builder extends OtlpHttpExporterBuilder<Builder> {
        private Builder() {
            super("http://localhost:4318/v1/logs", "logs");
        }

        @Override
        Builder self() {
            return this;
        }

        public OtlpHttpLogExporter build() {
            return new OtlpHttpLogExporter(newSender());
        }
    }
}
