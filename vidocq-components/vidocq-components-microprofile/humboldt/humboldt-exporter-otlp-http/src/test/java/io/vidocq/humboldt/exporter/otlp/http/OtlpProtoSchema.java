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

import io.vidocq.champollion.protobuf.CodedInputStream;
import io.vidocq.champollion.protobuf.Descriptors.Cardinality;
import io.vidocq.champollion.protobuf.Descriptors.Descriptor;
import io.vidocq.champollion.protobuf.Descriptors.FieldDescriptor;
import io.vidocq.champollion.protobuf.Descriptors.FileDescriptor;
import io.vidocq.champollion.protobuf.FieldType;
import io.vidocq.champollion.protobuf.WireFormat;
import io.vidocq.champollion.protobuf.codegen.ProtoAst.ProtoFile;
import io.vidocq.champollion.protobuf.codegen.SchemaResolver;
import io.vidocq.champollion.protobuf.codegen.internal.ProtoParser;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The official OTLP messages, and two readers that turn an encoded batch into the same tree, so that the protobuf
 * and the JSON encodings of a batch compare with {@code equals}.
 *
 * <p>The schema is the {@code .proto} files of opentelemetry-proto v1.5.0, copied unchanged under
 * {@code src/test/resources/opentelemetry/proto} (Apache-2.0), read with Champollion's {@code .proto} parser and
 * schema resolver. Nothing of the encoder under test is used to read the bytes: field numbers, wire types and
 * nesting come from the official files only.</p>
 *
 * <p>The tree is a map per message, keyed by the JSON name of each field: integers as {@link Long}, floating-point
 * numbers as {@link Double}, trace and span ids as lowercase hex and other bytes as base64 (the OTLP/JSON mapping).
 * A proto3 field without presence that holds its default value is left out, as the binary encoding leaves it out;
 * a message, an {@code optional} field and a member of a {@code oneof} are kept, since their presence matters.</p>
 */
final class OtlpProtoSchema {

    static final String TRACES_REQUEST = "opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest";
    static final String METRICS_REQUEST = "opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest";
    static final String LOGS_REQUEST = "opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest";

    private static final List<String> FILES = List.of(
            "opentelemetry/proto/common/v1/common.proto",
            "opentelemetry/proto/resource/v1/resource.proto",
            "opentelemetry/proto/trace/v1/trace.proto",
            "opentelemetry/proto/metrics/v1/metrics.proto",
            "opentelemetry/proto/logs/v1/logs.proto",
            "opentelemetry/proto/collector/trace/v1/trace_service.proto",
            "opentelemetry/proto/collector/metrics/v1/metrics_service.proto",
            "opentelemetry/proto/collector/logs/v1/logs_service.proto");

    /** Ids written as hex in OTLP/JSON (and not base64, as the proto3 JSON mapping would). */
    private static final Set<String> HEX_IDS = Set.of("trace_id", "span_id", "parent_span_id");

    /** The {@code oneof} members of the OTLP messages Humboldt writes: their default value is still present. */
    private static final Set<String> ONEOF_MEMBERS = Set.of(
            "opentelemetry.proto.common.v1.AnyValue.string_value",
            "opentelemetry.proto.common.v1.AnyValue.bool_value",
            "opentelemetry.proto.common.v1.AnyValue.int_value",
            "opentelemetry.proto.common.v1.AnyValue.double_value",
            "opentelemetry.proto.common.v1.AnyValue.bytes_value",
            "opentelemetry.proto.metrics.v1.NumberDataPoint.as_double",
            "opentelemetry.proto.metrics.v1.NumberDataPoint.as_int");

    private static final Map<String, Descriptor> MESSAGES = load();

    private OtlpProtoSchema() {}

    private static Map<String, Descriptor> load() {
        List<ProtoFile> files = new ArrayList<>();
        for (String file : FILES) {
            try (InputStream in = OtlpProtoSchema.class.getClassLoader().getResourceAsStream(file)) {
                if (in == null) throw new IllegalStateException("missing test resource " + file);
                String source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                files.add(ProtoParser.parse(file, withoutOneofBlocks(source)));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        Map<String, Descriptor> messages = new HashMap<>();
        for (FileDescriptor file : SchemaResolver.resolveAll(files).values()) {
            file.messageTypes().forEach(message -> index(message, messages));
        }
        return messages;
    }

    /**
     * Lifts the members of each {@code oneof} block to plain fields of their message. Champollion's {@code .proto}
     * parser skips a {@code oneof} block with its fields (BUG-20261127-84), and AnyValue, Metric and
     * NumberDataPoint keep theirs in one. On the wire a member is an ordinary field; {@link #ONEOF_MEMBERS} keeps
     * the presence of the ones Humboldt writes.
     */
    private static String withoutOneofBlocks(String source) {
        StringBuilder out = new StringBuilder(source.length());
        boolean inOneof = false;
        for (String line : source.split("\n", -1)) {
            String code = line.strip();
            if (code.startsWith("oneof ") && code.endsWith("{")) {
                inOneof = true;
            } else if (inOneof && code.equals("}")) {
                inOneof = false;
            } else {
                out.append(line).append('\n');
            }
        }
        return out.toString();
    }

    private static void index(Descriptor message, Map<String, Descriptor> messages) {
        messages.put(message.fullName(), message);
        message.nestedMessageTypes().forEach(nested -> index(nested, messages));
    }

    private static Descriptor message(String fullName) {
        Descriptor message = MESSAGES.get(fullName);
        if (message == null) throw new AssertionError("no message " + fullName + " in " + MESSAGES.keySet());
        return message;
    }

    // ------------------------------------------------------------------ protobuf

    /** Reads {@code bytes} as a {@code type} message; fails on a field, a wire type or a length the schema denies. */
    static Map<String, Object> decode(String type, byte[] bytes) {
        try {
            CodedInputStream in = CodedInputStream.newInstance(bytes);
            Map<String, Object> tree = readMessage(message(type), in);
            if (!in.isAtEnd()) throw new AssertionError("bytes left after the message");
            return tree;
        } catch (IOException e) {
            throw new AssertionError("not a valid " + type + ": " + e.getMessage(), e);
        }
    }

    private static Map<String, Object> readMessage(Descriptor message, CodedInputStream in) throws IOException {
        Map<String, Object> fields = new LinkedHashMap<>();
        int tag;
        while ((tag = in.readTag()) != 0) {
            int number = tag >>> 3;
            int wireType = tag & 7;
            FieldDescriptor field = message.findFieldByNumber(number);
            if (field == null) throw new AssertionError("field " + number + " is not in " + message.fullName());
            String where = message.fullName() + "." + field.name();
            if (field.isRepeated() && field.type().packable() && wireType == WireFormat.WIRETYPE_LENGTH_DELIMITED) {
                int oldLimit = in.pushLimit(in.readRawVarint32());
                while (in.getBytesUntilLimit() > 0) {
                    add(fields, field, readScalar(field, in));
                }
                in.popLimit(oldLimit);
                continue;
            }
            if (wireType != field.type().wireType()) {
                throw new AssertionError(where + " written with wire type " + wireType);
            }
            Object value;
            if (field.type() == FieldType.MESSAGE) {
                int oldLimit = in.pushLimit(in.readRawVarint32());
                value = readMessage(message(field.messageTypeName()), in);
                in.popLimit(oldLimit);
            } else {
                value = readScalar(field, in);
            }
            if (field.isRepeated()) {
                add(fields, field, value);
            } else if (fields.put(field.jsonName(), value) != null) {
                throw new AssertionError(where + " written twice");
            }
        }
        return withoutDefaults(message, fields);
    }

    @SuppressWarnings("unchecked")
    private static void add(Map<String, Object> fields, FieldDescriptor field, Object value) {
        ((List<Object>) fields.computeIfAbsent(field.jsonName(), k -> new ArrayList<>())).add(value);
    }

    private static Object readScalar(FieldDescriptor field, CodedInputStream in) throws IOException {
        return switch (field.type()) {
            case INT32, INT64, UINT32, UINT64, ENUM -> in.readRawVarint64();
            case SINT32, SINT64 -> CodedInputStream.decodeZigZag64(in.readRawVarint64());
            case BOOL -> in.readRawVarint64() != 0;
            case FIXED32, SFIXED32 -> (long) in.readRawLittleEndian32();
            case FIXED64, SFIXED64 -> in.readRawLittleEndian64();
            case FLOAT -> (double) Float.intBitsToFloat(in.readRawLittleEndian32());
            case DOUBLE -> Double.longBitsToDouble(in.readRawLittleEndian64());
            case STRING -> in.readStringRequireUtf8();
            case BYTES -> {
                byte[] bytes = in.readBytes();
                yield HEX_IDS.contains(field.name())
                        ? HexFormat.of().formatHex(bytes)
                        : Base64.getEncoder().encodeToString(bytes);
            }
            default -> throw new AssertionError("no OTLP field has the type " + field.type());
        };
    }

    // ------------------------------------------------------------------ JSON

    /** Reads the OTLP/JSON {@code json} as a {@code type} message, into the tree {@link #decode} builds. */
    static Map<String, Object> fromJson(String type, String json) {
        try (JsonReader reader = Json.createReader(new StringReader(json))) {
            return jsonMessage(message(type), reader.readObject());
        }
    }

    private static Map<String, Object> jsonMessage(Descriptor message, JsonObject object) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (Map.Entry<String, JsonValue> entry : object.entrySet()) {
            FieldDescriptor field = message.findFieldByJsonName(entry.getKey());
            if (field == null) {
                throw new AssertionError("JSON field " + entry.getKey() + " is not in " + message.fullName());
            }
            if (field.isRepeated()) {
                List<Object> values = new ArrayList<>();
                for (JsonValue item : (JsonArray) entry.getValue()) values.add(jsonValue(field, item));
                fields.put(field.jsonName(), values);
            } else {
                fields.put(field.jsonName(), jsonValue(field, entry.getValue()));
            }
        }
        return withoutDefaults(message, fields);
    }

    private static Object jsonValue(FieldDescriptor field, JsonValue value) {
        return switch (field.type()) {
            case MESSAGE -> jsonMessage(message(field.messageTypeName()), (JsonObject) value);
            case INT32, INT64, UINT32, UINT64, SINT32, SINT64, FIXED32, SFIXED32, FIXED64, SFIXED64, ENUM ->
                    value instanceof JsonString s ? Long.parseLong(s.getString()) : ((JsonNumber) value).longValue();
            case FLOAT, DOUBLE ->
                    value instanceof JsonString s ? Double.parseDouble(s.getString()) : ((JsonNumber) value).doubleValue();
            case BOOL -> value == JsonValue.TRUE;
            case STRING, BYTES -> ((JsonString) value).getString();
            default -> throw new AssertionError("no OTLP field has the type " + field.type());
        };
    }

    // ------------------------------------------------------------------ shared

    private static Map<String, Object> withoutDefaults(Descriptor message, Map<String, Object> fields) {
        for (FieldDescriptor field : message.fields()) {
            Object value = fields.get(field.jsonName());
            boolean implicit = field.cardinality() == Cardinality.IMPLICIT
                    && field.type() != FieldType.MESSAGE
                    && !ONEOF_MEMBERS.contains(message.fullName() + "." + field.name());
            boolean emptyList = value instanceof List<?> list && list.isEmpty();
            if (emptyList || (implicit && isDefault(value))) {
                fields.remove(field.jsonName());
            }
        }
        return fields;
    }

    private static boolean isDefault(Object value) {
        return switch (value) {
            case Long l -> l == 0L;
            case Double d -> d == 0.0 && Double.doubleToRawLongBits(d) == 0L;
            case Boolean b -> !b;
            case String s -> s.isEmpty();
            case null, default -> false;
        };
    }
}
