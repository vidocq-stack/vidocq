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
import io.vidocq.humboldt.sdk.trace.data.EventData;
import io.vidocq.humboldt.sdk.trace.data.LinkData;
import io.vidocq.humboldt.sdk.trace.data.SpanData;
import io.vidocq.humboldt.sdk.trace.data.StatusData;

import java.util.Collection;

import static io.vidocq.humboldt.exporter.otlp.http.internal.OtlpProtoCommon.writeAttributes;

/**
 * Encodes a collection of {@link SpanData} as an OTLP {@code ExportTraceServiceRequest} in the protobuf binary
 * format ({@code application/x-protobuf}).
 *
 * <p>Schema: <a href="https://github.com/open-telemetry/opentelemetry-proto/blob/main/opentelemetry/proto/trace/v1/trace.proto">trace.proto</a>
 * and {@code collector/trace/v1/trace_service.proto}. It carries the fields {@link OtlpJsonEncoder} writes in
 * OTLP/JSON: the two encodings of a batch decode to the same message.</p>
 */
public final class OtlpProtoSpanEncoder {

    private OtlpProtoSpanEncoder() {}

    public static byte[] encode(Collection<SpanData> spans) {
        return OtlpProtoCommon.encodeRequest(spans, SpanData::resource, SpanData::instrumentationScope,
                OtlpProtoSpanEncoder::writeSpan);
    }

    /**
     * {@code Span}: {@code trace_id = 1}, {@code span_id = 2}, {@code parent_span_id = 4}, {@code name = 5},
     * {@code kind = 6}, {@code start_time_unix_nano = 7}, {@code end_time_unix_nano = 8}, {@code attributes = 9},
     * {@code events = 11}, {@code links = 13}, {@code status = 15}.
     */
    private static void writeSpan(ProtoWriter out, SpanData span) {
        out.bytesField(1, span.spanContext().getTraceIdBytes());
        out.bytesField(2, span.spanContext().getSpanIdBytes());
        SpanContext parent = span.parentSpanContext();
        if (parent != null && parent.isValid()) {
            out.bytesField(4, parent.getSpanIdBytes());
        }
        out.stringField(5, span.name());
        out.varintField(6, switch (span.kind()) {
            case INTERNAL -> 1;
            case SERVER -> 2;
            case CLIENT -> 3;
            case PRODUCER -> 4;
            case CONSUMER -> 5;
        });
        out.fixed64Field(7, span.startEpochNanos());
        out.fixed64Field(8, span.endEpochNanos());
        writeAttributes(out, 9, span.attributes());
        for (EventData event : span.events()) {
            int mark = out.beginMessage(11);
            out.fixed64Field(1, event.epochNanos());
            out.stringField(2, event.name());
            writeAttributes(out, 3, event.attributes());
            out.endMessage(mark);
        }
        for (LinkData link : span.links()) {
            int mark = out.beginMessage(13);
            out.bytesField(1, link.spanContext().getTraceIdBytes());
            out.bytesField(2, link.spanContext().getSpanIdBytes());
            writeAttributes(out, 4, link.attributes());
            out.endMessage(mark);
        }
        writeStatus(out, span.status());
    }

    /** {@code Status}: {@code message = 2}, {@code code = 3} (UNSET 0, OK 1, ERROR 2). */
    private static void writeStatus(ProtoWriter out, StatusData status) {
        int mark = out.beginMessage(15);
        out.stringField(2, status.description());
        out.varintField(3, switch (status.code()) {
            case UNSET -> 0;
            case OK -> 1;
            case ERROR -> 2;
        });
        out.endMessage(mark);
    }
}
