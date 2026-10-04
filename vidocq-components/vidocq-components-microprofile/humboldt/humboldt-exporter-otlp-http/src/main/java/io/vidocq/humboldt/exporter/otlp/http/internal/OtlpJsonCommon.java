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

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.AttributeType;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.KeyValue;
import io.opentelemetry.api.common.Value;
import io.vidocq.humboldt.sdk.common.InstrumentationScope;
import io.vidocq.humboldt.sdk.common.Resource;

import java.nio.ByteBuffer;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * OTLP/JSON helpers shared among the 3 encoders (spans, metrics, logs).
 *
 * <p>Everything that is trivially identical across {@code resourceSpans} /
 * {@code resourceMetrics} / {@code resourceLogs}: JSON escaping,
 * {@code AnyValue}, {@code KeyValue} array, the {@code "resource"} block and
 * the {@code "scope"} header. The signal-specific business logic
 * (span fields, metric data points, log record fields) stays in its own encoder.</p>
 */
final class OtlpJsonCommon {

    private OtlpJsonCommon() {}

    /**
     * Minimal JSON escaping — RFC 8259 §7. Escapes the quote, the backslash,
     * and control characters (b/f/n/r/t and others as u00XX).
     */
    static void appendString(StringBuilder sb, String s) {
        sb.append('"');
        if (s == null) {
            sb.append('"');
            return;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    /**
     * Encodes an {@link Attributes} as a JSON array {@code [{"key":"k","value":AnyValue}, ...]}.
     */
    static void writeAttributesArray(StringBuilder sb, Attributes attrs) {
        sb.append('[');
        List<Map.Entry<AttributeKey<?>, Object>> entries = new ArrayList<>(attrs.size());
        attrs.forEach((k, v) -> entries.add(new AbstractMap.SimpleEntry<>(k, v)));
        boolean first = true;
        for (var e : entries) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"key\":");
            appendString(sb, e.getKey().getKey());
            sb.append(",\"value\":");
            writeAnyValue(sb, e.getKey().getType(), e.getValue());
            sb.append('}');
        }
        sb.append(']');
    }

    /**
     * Encodes a value as an OTLP/JSON {@code AnyValue} — supports all
     * {@link AttributeType} variants from the OTel public API: scalars, the
     * array variants (encoded as {@code arrayValue.values}) and complex
     * {@link AttributeType#VALUE} attributes (see {@link #writeValue}).
     */
    static void writeAnyValue(StringBuilder sb, AttributeType type, Object v) {
        if (type == AttributeType.VALUE) {
            writeValue(sb, (Value<?>) v);
            return;
        }
        sb.append('{');
        switch (type) {
            case STRING -> {
                sb.append("\"stringValue\":");
                appendString(sb, (String) v);
            }
            case BOOLEAN -> sb.append("\"boolValue\":").append((boolean) v);
            case LONG -> sb.append("\"intValue\":\"").append((long) v).append('"');
            case DOUBLE -> sb.append("\"doubleValue\":").append((double) v);
            case STRING_ARRAY, BOOLEAN_ARRAY, LONG_ARRAY, DOUBLE_ARRAY -> {
                sb.append("\"arrayValue\":{\"values\":[");
                List<?> list = (List<?>) v;
                AttributeType elementType = switch (type) {
                    case STRING_ARRAY -> AttributeType.STRING;
                    case BOOLEAN_ARRAY -> AttributeType.BOOLEAN;
                    case LONG_ARRAY -> AttributeType.LONG;
                    case DOUBLE_ARRAY -> AttributeType.DOUBLE;
                    default -> throw new IllegalStateException();
                };
                boolean first = true;
                for (Object item : list) {
                    if (!first) sb.append(',');
                    first = false;
                    writeAnyValue(sb, elementType, item);
                }
                sb.append("]}");
            }
        }
        sb.append('}');
    }

    /**
     * Encodes a complex {@link Value} (an {@link AttributeType#VALUE} attribute, set for example through
     * {@code setAttribute(String, Value)}) as an OTLP/JSON {@code AnyValue}, recursively: scalars as
     * {@code stringValue}/{@code boolValue}/{@code intValue}/{@code doubleValue}, arrays as
     * {@code arrayValue.values}, maps as {@code kvlistValue.values} of {@code {"key","value"}} pairs,
     * bytes as base64 {@code bytesValue}, and an empty value as an {@code AnyValue} with no field set.
     */
    static void writeValue(StringBuilder sb, Value<?> value) {
        switch (value.getType()) {
            case STRING -> writeAnyValue(sb, AttributeType.STRING, value.getValue());
            case BOOLEAN -> writeAnyValue(sb, AttributeType.BOOLEAN, value.getValue());
            case LONG -> writeAnyValue(sb, AttributeType.LONG, value.getValue());
            case DOUBLE -> writeAnyValue(sb, AttributeType.DOUBLE, value.getValue());
            case BYTES -> {
                ByteBuffer buffer = (ByteBuffer) value.getValue();
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                sb.append("{\"bytesValue\":");
                appendString(sb, Base64.getEncoder().encodeToString(bytes));
                sb.append('}');
            }
            case ARRAY -> {
                sb.append("{\"arrayValue\":{\"values\":[");
                boolean first = true;
                for (Object item : (List<?>) value.getValue()) {
                    if (!first) sb.append(',');
                    first = false;
                    writeValue(sb, (Value<?>) item);
                }
                sb.append("]}}");
            }
            case KEY_VALUE_LIST -> {
                sb.append("{\"kvlistValue\":{\"values\":[");
                boolean first = true;
                for (Object item : (List<?>) value.getValue()) {
                    KeyValue entry = (KeyValue) item;
                    if (!first) sb.append(',');
                    first = false;
                    sb.append("{\"key\":");
                    appendString(sb, entry.getKey());
                    sb.append(",\"value\":");
                    writeValue(sb, entry.getValue());
                    sb.append('}');
                }
                sb.append("]}}");
            }
            // ValueType.EMPTY (and any variant a later API may add): an AnyValue with no field set
            default -> sb.append("{}");
        }
    }

    /**
     * Encodes the {@code "resource":{"attributes":[...]}} block.
     * The {@code schemaUrl} must be written separately by the caller (placement
     * differs per signal in the OTLP schema).
     */
    static void writeResource(StringBuilder sb, Resource resource) {
        sb.append("\"resource\":{\"attributes\":");
        writeAttributesArray(sb, resource.attributes());
        sb.append("}");
    }

    /**
     * Encodes the {@code "scope":{"name":...,"version":...,"attributes":...}} header.
     * <p>The caller must close the enclosing {@code scope*} object with its own
     * spans/metrics/logRecords array and optional {@code schemaUrl}.</p>
     */
    static void writeScopeHeader(StringBuilder sb, InstrumentationScope scope) {
        sb.append("\"scope\":{\"name\":");
        appendString(sb, scope.name());
        if (scope.version() != null) {
            sb.append(",\"version\":");
            appendString(sb, scope.version());
        }
        if (!scope.attributes().isEmpty()) {
            sb.append(",\"attributes\":");
            writeAttributesArray(sb, scope.attributes());
        }
        sb.append('}');
    }
}
