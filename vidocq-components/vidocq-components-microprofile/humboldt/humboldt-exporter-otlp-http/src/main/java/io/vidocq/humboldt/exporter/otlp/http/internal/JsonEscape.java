package io.vidocq.humboldt.exporter.otlp.http.internal;

/**
 * Échappement JSON minimal — sans dépendance externe (champollion ou Jackson)
 * puisque l'usage est limité au schéma OTLP/JSON.
 */
final class JsonEscape {

    private JsonEscape() {}

    static void appendEscaped(StringBuilder sb, String s) {
        sb.append('"');
        if (s == null) {
            sb.append("\"");
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
}
