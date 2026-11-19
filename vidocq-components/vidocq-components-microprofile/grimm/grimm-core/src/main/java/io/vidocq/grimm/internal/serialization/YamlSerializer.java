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
package io.vidocq.grimm.internal.serialization;

import org.eclipse.microprofile.openapi.models.OpenAPI;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public final class YamlSerializer {

    public String serialize(OpenAPI openAPI) {
        if (openAPI == null) {
            throw new NullPointerException("openAPI must not be null");
        }
        StringBuilder builder = new StringBuilder();
        writeYaml(OpenApiValueMapper.toSerializable(openAPI), builder, 0);
        return builder.toString();
    }

    private void writeYaml(Object value, StringBuilder builder, int indent) {
        if (value instanceof Map<?, ?> mapValue) {
            writeMap(mapValue, builder, indent);
            return;
        }
        if (value instanceof List<?> listValue) {
            writeList(listValue, builder, indent);
            return;
        }

        appendIndent(builder, indent);
        builder.append(writeScalar(value)).append('\n');
    }

    private void writeMap(Map<?, ?> mapValue, StringBuilder builder, int indent) {
        for (Map.Entry<?, ?> entry : mapValue.entrySet()) {
            appendIndent(builder, indent);
            builder.append(writeKey(entry.getKey())).append(':');
            writeEntryValue(entry.getValue(), builder, indent);
        }
    }

    /** Writes the value after a map key or a list dash: inline when scalar or empty, nested otherwise. */
    private void writeEntryValue(Object value, StringBuilder builder, int indent) {
        switch (value) {
            case Map<?, ?> map when map.isEmpty() -> builder.append(" {}\n");
            case List<?> list when list.isEmpty() -> builder.append(" []\n");
            case Map<?, ?> _, List<?> _ -> {
                builder.append('\n');
                writeYaml(value, builder, indent + 2);
            }
            case null, default -> builder.append(' ').append(writeScalar(value)).append('\n');
        }
    }

    private String writeKey(Object key) {
        if (key == null) {
            return "\"null\"";
        }
        String text = String.valueOf(key);
        if (text.matches("[A-Za-z0-9_./-]+")) {
            return text;
        }
        return '"' + escapeString(text) + '"';
    }

    private void writeList(List<?> listValue, StringBuilder builder, int indent) {
        for (Object item : listValue) {
            appendIndent(builder, indent);
            builder.append('-');
            writeEntryValue(item, builder, indent);
        }
    }

    private String writeScalar(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof BigDecimal decimal) {
            // Plain notation, as in JsonSerializer (which explains why).
            return decimal.toPlainString();
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        return '"' + escapeString(String.valueOf(value)) + '"';
    }

    private String escapeString(String raw) {
        // A JSON string escape is a valid YAML double-quoted escape.
        return JsonSerializer.escapeJson(raw);
    }

    private void appendIndent(StringBuilder builder, int indent) {
        builder.append(" ".repeat(Math.max(0, indent)));
    }
}

