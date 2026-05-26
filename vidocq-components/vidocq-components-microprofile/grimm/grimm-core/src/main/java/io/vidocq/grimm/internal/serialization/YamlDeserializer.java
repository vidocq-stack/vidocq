package io.vidocq.grimm.internal.serialization;

import org.eclipse.microprofile.openapi.models.OpenAPI;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class YamlDeserializer {

    public OpenAPI deserialize(String yaml) {
        if (yaml == null) {
            throw new NullPointerException("yaml must not be null");
        }

        NodeParser parser = new NodeParser(yaml);
        Object parsed = parser.parseDocument();
        return OpenApiModelMapper.toOpenApi(parsed);
    }

    private static final class NodeParser {
        private final List<String> lines;
        private int index;

        private NodeParser(String yaml) {
            this.lines = yaml.lines().toList();
        }

        private Object parseDocument() {
            skipBlankLines();
            if (index >= lines.size()) {
                return Map.of();
            }
            return parseNode(indentOf(lines.get(index)));
        }

        private Object parseNode(int expectedIndent) {
            if (index >= lines.size()) {
                return null;
            }
            String line = lines.get(index);
            if (indentOf(line) != expectedIndent) {
                return null;
            }
            String trimmed = line.trim();
            if (trimmed.startsWith("-")) {
                return parseList(expectedIndent);
            }
            return parseMap(expectedIndent);
        }

        private Map<String, Object> parseMap(int expectedIndent) {
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();

            while (index < lines.size()) {
                skipBlankLines();
                if (index >= lines.size()) {
                    break;
                }

                String line = lines.get(index);
                int currentIndent = indentOf(line);
                if (currentIndent < expectedIndent) {
                    break;
                }
                if (currentIndent > expectedIndent || line.trim().startsWith("-")) {
                    break;
                }

                String trimmed = line.trim();
                int colon = trimmed.indexOf(':');
                if (colon < 0) {
                    throw new IllegalArgumentException("Invalid YAML line: " + line);
                }

                String key = trimmed.substring(0, colon);
                String rest = trimmed.substring(colon + 1).trim();
                index++;

                if (rest.isEmpty()) {
                    skipBlankLines();
                    if (index >= lines.size() || indentOf(lines.get(index)) <= currentIndent) {
                        result.put(key, null);
                    } else {
                        result.put(key, parseNode(currentIndent + 2));
                    }
                } else {
                    result.put(key, parseScalar(rest));
                }
            }

            return result;
        }

        private List<Object> parseList(int expectedIndent) {
            ArrayList<Object> result = new ArrayList<>();

            while (index < lines.size()) {
                skipBlankLines();
                if (index >= lines.size()) {
                    break;
                }

                String line = lines.get(index);
                int currentIndent = indentOf(line);
                if (currentIndent != expectedIndent || !line.trim().startsWith("-")) {
                    break;
                }

                String trimmed = line.trim();
                String rest = trimmed.substring(1).trim();
                index++;

                if (rest.isEmpty()) {
                    skipBlankLines();
                    if (index >= lines.size() || indentOf(lines.get(index)) <= currentIndent) {
                        result.add(null);
                    } else {
                        result.add(parseNode(currentIndent + 2));
                    }
                } else {
                    result.add(parseScalar(rest));
                }
            }

            return result;
        }

        private Object parseScalar(String raw) {
            if ("null".equals(raw)) {
                return null;
            }
            if ("true".equals(raw)) {
                return Boolean.TRUE;
            }
            if ("false".equals(raw)) {
                return Boolean.FALSE;
            }

            if (raw.startsWith("\"") && raw.endsWith("\"") && raw.length() >= 2) {
                return unescape(raw.substring(1, raw.length() - 1));
            }

            if (raw.matches("-?\\d+")) {
                return Long.parseLong(raw);
            }
            if (raw.matches("-?\\d+\\.\\d+")) {
                return Double.parseDouble(raw);
            }

            return raw;
        }

        private String unescape(String value) {
            StringBuilder out = new StringBuilder(value.length());
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c == '\\' && i + 1 < value.length()) {
                    char next = value.charAt(++i);
                    switch (next) {
                        case 'n' -> out.append('\n');
                        case 'r' -> out.append('\r');
                        case 't' -> out.append('\t');
                        case '\\' -> out.append('\\');
                        case '"' -> out.append('"');
                        default -> out.append(next);
                    }
                } else {
                    out.append(c);
                }
            }
            return out.toString();
        }

        private void skipBlankLines() {
            while (index < lines.size() && lines.get(index).trim().isEmpty()) {
                index++;
            }
        }

        private int indentOf(String line) {
            int indent = 0;
            while (indent < line.length() && line.charAt(indent) == ' ') {
                indent++;
            }
            return indent;
        }
    }
}

