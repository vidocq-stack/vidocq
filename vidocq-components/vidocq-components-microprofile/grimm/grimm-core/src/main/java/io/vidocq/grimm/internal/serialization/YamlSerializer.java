package io.vidocq.grimm.internal.serialization;

import org.eclipse.microprofile.openapi.models.OpenAPI;

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
            Object child = entry.getValue();
            if (child instanceof Map<?, ?> childMap) {
                if (childMap.isEmpty()) {
                    builder.append(" {}\n");
                } else {
                    builder.append('\n');
                    writeYaml(childMap, builder, indent + 2);
                }
            } else if (child instanceof List<?> childList) {
                if (childList.isEmpty()) {
                    builder.append(" []\n");
                } else {
                    builder.append('\n');
                    writeYaml(childList, builder, indent + 2);
                }
            } else {
                builder.append(' ').append(writeScalar(child)).append('\n');
            }
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
            if (item instanceof Map<?, ?> || item instanceof List<?>) {
                builder.append('\n');
                writeYaml(item, builder, indent + 2);
            } else {
                builder.append(' ').append(writeScalar(item)).append('\n');
            }
        }
    }

    private String writeScalar(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        return '"' + escapeString(String.valueOf(value)) + '"';
    }

    private String escapeString(String raw) {
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

    private void appendIndent(StringBuilder builder, int indent) {
        builder.append(" ".repeat(Math.max(0, indent)));
    }
}

