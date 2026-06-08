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

import java.util.List;
import java.util.Map;

public final class JsonSerializer {

    public String serialize(OpenAPI openAPI) {
        if (openAPI == null) {
            throw new NullPointerException("openAPI must not be null");
        }
        Object tree = OpenApiValueMapper.toSerializable(openAPI);
        return writeJson(tree);
    }

    private String writeJson(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String stringValue) {
            return '"' + escapeJson(stringValue) + '"';
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof List<?> listValue) {
            return writeJsonArray(listValue);
        }
        if (value instanceof Map<?, ?> mapValue) {
            return writeJsonObject(mapValue);
        }
        return '"' + escapeJson(String.valueOf(value)) + '"';
    }

    private String writeJsonObject(Map<?, ?> mapValue) {
        StringBuilder builder = new StringBuilder();
        builder.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> entry : mapValue.entrySet()) {
            if (!first) {
                builder.append(',');
            }
            first = false;
            builder.append('"').append(escapeJson(String.valueOf(entry.getKey()))).append('"').append(':');
            builder.append(writeJson(entry.getValue()));
        }
        builder.append('}');
        return builder.toString();
    }

    private String writeJsonArray(List<?> listValue) {
        StringBuilder builder = new StringBuilder();
        builder.append('[');
        boolean first = true;
        for (Object item : listValue) {
            if (!first) {
                builder.append(',');
            }
            first = false;
            builder.append(writeJson(item));
        }
        builder.append(']');
        return builder.toString();
    }

    private String escapeJson(String raw) {
        StringBuilder escaped = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> escaped.append(c);
            }
        }
        return escaped.toString();
    }
}

