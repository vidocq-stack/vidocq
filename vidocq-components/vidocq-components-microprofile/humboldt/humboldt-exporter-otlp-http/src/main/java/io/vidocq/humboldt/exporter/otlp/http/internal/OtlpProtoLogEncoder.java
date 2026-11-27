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
package io.vidocq.humboldt.exporter.otlp.http.internal;

import io.opentelemetry.api.trace.SpanContext;
import io.vidocq.humboldt.sdk.log.data.LogRecordData;

import java.util.Collection;

import static io.vidocq.humboldt.exporter.otlp.http.internal.OtlpProtoCommon.writeAttributes;
import static io.vidocq.humboldt.exporter.otlp.http.internal.OtlpProtoCommon.writeValue;

/**
 * Encodes a collection of {@link LogRecordData} as an OTLP {@code ExportLogsServiceRequest} in the protobuf binary
 * format ({@code application/x-protobuf}).
 *
 * <p>Schema: <a href="https://github.com/open-telemetry/opentelemetry-proto/blob/main/opentelemetry/proto/logs/v1/logs.proto">logs.proto</a>
 * and {@code collector/logs/v1/logs_service.proto}. It carries the fields {@link OtlpJsonLogEncoder} writes in
 * OTLP/JSON.</p>
 */
public final class OtlpProtoLogEncoder {

    private OtlpProtoLogEncoder() {}

    public static byte[] encode(Collection<LogRecordData> records) {
        return OtlpProtoCommon.encodeRequest(records, LogRecordData::resource, LogRecordData::scope,
                OtlpProtoLogEncoder::writeRecord);
    }

    /**
     * {@code LogRecord}: {@code time_unix_nano = 1}, {@code severity_number = 2}, {@code severity_text = 3},
     * {@code body = 5}, {@code attributes = 6}, {@code flags = 8}, {@code trace_id = 9}, {@code span_id = 10},
     * {@code observed_time_unix_nano = 11}, {@code event_name = 12}.
     */
    private static void writeRecord(ProtoWriter out, LogRecordData logRecord) {
        out.fixed64Field(1, logRecord.timestampEpochNanos());
        out.varintField(2, Math.max(0, logRecord.severity().getSeverityNumber()));
        out.stringField(3, logRecord.severityText());
        if (logRecord.bodyValue() != null) {
            // a string body is a string_value; a structured body keeps its AnyValue shape
            writeValue(out, 5, logRecord.bodyValue());
        }
        writeAttributes(out, 6, logRecord.attributes());
        SpanContext spanContext = logRecord.spanContext();
        if (spanContext != null && spanContext.isValid()) {
            out.fixed32Field(8, spanContext.getTraceFlags().asByte() & 0xFF);
            out.bytesField(9, spanContext.getTraceIdBytes());
            out.bytesField(10, spanContext.getSpanIdBytes());
        }
        out.fixed64Field(11, logRecord.observedEpochNanos());
        out.stringField(12, logRecord.eventName());
    }
}
