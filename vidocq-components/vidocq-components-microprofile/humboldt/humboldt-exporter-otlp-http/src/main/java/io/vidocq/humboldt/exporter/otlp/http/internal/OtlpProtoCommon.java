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
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * What the three OTLP/protobuf encoders share: the request skeleton, the same for every signal, and the messages
 * of {@code common.proto} and {@code resource.proto} ({@code AnyValue}, {@code KeyValue}, {@code Resource},
 * {@code InstrumentationScope}).
 *
 * <p>The three export requests have one shape: {@code Export*ServiceRequest} holds, in field 1, one
 * {@code Resource*} per resource ({@code resource = 1}, {@code scope_* = 2}, {@code schema_url = 3}), and each
 * {@code Scope*} holds {@code scope = 1}, the signal's items in field 2 and {@code schema_url = 3}. Only the item
 * differs, written by the signal's encoder.</p>
 */
final class OtlpProtoCommon {

    private OtlpProtoCommon() {}

    /**
     * Encodes an {@code Export*ServiceRequest}: {@code items} grouped by resource, then by scope, each item written
     * by {@code writeItem} as the content of its embedded message.
     */
    static <T> byte[] encodeRequest(Collection<T> items, Function<T, Resource> resourceOf,
                                    Function<T, InstrumentationScope> scopeOf, BiConsumer<ProtoWriter, T> writeItem) {
        ProtoWriter out = new ProtoWriter();
        for (var byResource : group(items, resourceOf, scopeOf).entrySet()) {
            int resourceItems = out.beginMessage(1);
            Resource resource = byResource.getKey();
            int resourceMark = out.beginMessage(1);
            writeAttributes(out, 1, resource.attributes());
            out.endMessage(resourceMark);
            for (var byScope : byResource.getValue().entrySet()) {
                InstrumentationScope scope = byScope.getKey();
                int scopeItems = out.beginMessage(2);
                writeScope(out, scope);
                for (T item : byScope.getValue()) {
                    int itemMark = out.beginMessage(2);
                    writeItem.accept(out, item);
                    out.endMessage(itemMark);
                }
                out.stringField(3, scope.schemaUrl());
                out.endMessage(scopeItems);
            }
            out.stringField(3, resource.schemaUrl());
            out.endMessage(resourceItems);
        }
        return out.toByteArray();
    }

    /** Groups {@code items} by resource, then by scope, as the OTLP/JSON encoders do. */
    private static <T> Map<Resource, Map<InstrumentationScope, List<T>>> group(Collection<T> items,
            Function<T, Resource> resourceOf, Function<T, InstrumentationScope> scopeOf) {
        Map<Resource, Map<InstrumentationScope, List<T>>> grouped = new HashMap<>();
        for (T item : items) {
            grouped.computeIfAbsent(resourceOf.apply(item), r -> new HashMap<>())
                    .computeIfAbsent(scopeOf.apply(item), s -> new ArrayList<>())
                    .add(item);
        }
        return grouped;
    }

    /** {@code InstrumentationScope}: {@code name = 1}, {@code version = 2}, {@code attributes = 3}. */
    private static void writeScope(ProtoWriter out, InstrumentationScope scope) {
        int mark = out.beginMessage(1);
        out.stringField(1, scope.name());
        out.stringField(2, scope.version());
        writeAttributes(out, 3, scope.attributes());
        out.endMessage(mark);
    }

    /** Writes each attribute as a {@code KeyValue} of the repeated field {@code field}. */
    static void writeAttributes(ProtoWriter out, int field, Attributes attributes) {
        attributes.forEach((key, value) -> writeKeyValue(out, field, key, value));
    }

    private static void writeKeyValue(ProtoWriter out, int field, AttributeKey<?> key, Object value) {
        int mark = out.beginMessage(field);
        out.stringField(1, key.getKey());
        int valueMark = out.beginMessage(2);
        writeAnyValueContent(out, key.getType(), value);
        out.endMessage(valueMark);
        out.endMessage(mark);
    }

    /** Writes {@code value} as the {@code AnyValue} of field {@code field}. */
    static void writeValue(ProtoWriter out, int field, Value<?> value) {
        int mark = out.beginMessage(field);
        writeValueContent(out, value);
        out.endMessage(mark);
    }

    /**
     * The content of an {@code AnyValue} holding an attribute value: {@code string_value = 1},
     * {@code bool_value = 2}, {@code int_value = 3}, {@code double_value = 4}, the array attributes as
     * {@code array_value = 5} and a complex {@link AttributeType#VALUE} through {@link #writeValueContent}. A
     * {@code oneof} member is written even when it holds its default value.
     */
    private static void writeAnyValueContent(ProtoWriter out, AttributeType type, Object value) {
        switch (type) {
            case VALUE -> writeValueContent(out, (Value<?>) value);
            case STRING -> out.stringAlways(1, (String) value);
            case BOOLEAN -> out.boolAlways(2, (Boolean) value);
            case LONG -> out.varintAlways(3, (Long) value);
            case DOUBLE -> out.doubleAlways(4, (Double) value);
            case STRING_ARRAY, BOOLEAN_ARRAY, LONG_ARRAY, DOUBLE_ARRAY -> {
                AttributeType elementType = switch (type) {
                    case STRING_ARRAY -> AttributeType.STRING;
                    case BOOLEAN_ARRAY -> AttributeType.BOOLEAN;
                    case LONG_ARRAY -> AttributeType.LONG;
                    default -> AttributeType.DOUBLE;
                };
                int array = out.beginMessage(5);
                for (Object item : (List<?>) value) {
                    int element = out.beginMessage(1);
                    writeAnyValueContent(out, elementType, item);
                    out.endMessage(element);
                }
                out.endMessage(array);
            }
        }
    }

    /**
     * The content of an {@code AnyValue} holding a {@link Value}: scalars as for an attribute, bytes as
     * {@code bytes_value = 7}, an array as {@code array_value = 5}, a map as {@code kvlist_value = 6} of
     * {@code KeyValue}, and an empty value as an {@code AnyValue} with no field set.
     */
    private static void writeValueContent(ProtoWriter out, Value<?> value) {
        switch (value.getType()) {
            case STRING -> out.stringAlways(1, (String) value.getValue());
            case BOOLEAN -> out.boolAlways(2, (Boolean) value.getValue());
            case LONG -> out.varintAlways(3, (Long) value.getValue());
            case DOUBLE -> out.doubleAlways(4, (Double) value.getValue());
            case BYTES -> {
                ByteBuffer buffer = ((ByteBuffer) value.getValue()).duplicate();
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                out.bytesAlways(7, bytes);
            }
            case ARRAY -> {
                int array = out.beginMessage(5);
                for (Object item : (List<?>) value.getValue()) {
                    writeValue(out, 1, (Value<?>) item);
                }
                out.endMessage(array);
            }
            case KEY_VALUE_LIST -> {
                int list = out.beginMessage(6);
                for (Object item : (List<?>) value.getValue()) {
                    KeyValue entry = (KeyValue) item;
                    int mark = out.beginMessage(1);
                    out.stringField(1, entry.getKey());
                    writeValue(out, 2, entry.getValue());
                    out.endMessage(mark);
                }
                out.endMessage(list);
            }
            // ValueType.EMPTY (and any variant a later API may add): an AnyValue with no field set
            default -> {
                // nothing to write
            }
        }
    }
}
