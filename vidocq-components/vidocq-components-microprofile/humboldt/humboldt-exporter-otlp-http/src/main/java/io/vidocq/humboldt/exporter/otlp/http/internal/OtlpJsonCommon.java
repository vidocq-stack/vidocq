package io.vidocq.humboldt.exporter.otlp.http.internal;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.AttributeType;
import io.opentelemetry.api.common.Attributes;
import io.vidocq.humboldt.sdk.common.InstrumentationScope;
import io.vidocq.humboldt.sdk.common.Resource;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Helpers OTLP/JSON mutualisés entre les 3 encoders (spans, metrics, logs).
 *
 * <p>Tout ce qui est trivialement identique entre {@code resourceSpans} /
 * {@code resourceMetrics} / {@code resourceLogs} : escape JSON,
 * {@code AnyValue}, {@code KeyValue} array, bloc {@code "resource"} et
 * en-tête de {@code "scope"}. La logique métier spécifique à chaque signal
 * (span fields, metric data points, log record fields) reste dans son encoder.</p>
 */
final class OtlpJsonCommon {

    private OtlpJsonCommon() {}

    /**
     * Échappement JSON minimal — RFC 8259 §7. Échappe le quote, l'antislash,
     * et les chars de contrôle (b/f/n/r/t et autres en u00XX).
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
     * Encode un {@link Attributes} en tableau JSON {@code [{"key":"k","value":AnyValue}, ...]}.
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
     * Encode une valeur en {@code AnyValue} OTLP/JSON — supporte tous les
     * {@link AttributeType} de l'API publique OTel, y compris les variantes
     * array (encodées en {@code arrayValue.values}).
     */
    static void writeAnyValue(StringBuilder sb, AttributeType type, Object v) {
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
     * Encode le bloc {@code "resource":{"attributes":[...]}}.
     * Le {@code schemaUrl} doit être écrit séparément par l'appelant (placement
     * différent dans chaque signal selon le schéma OTLP).
     */
    static void writeResource(StringBuilder sb, Resource resource) {
        sb.append("\"resource\":{\"attributes\":");
        writeAttributesArray(sb, resource.attributes());
        sb.append("}");
    }

    /**
     * Encode l'en-tête {@code "scope":{"name":...,"version":...,"attributes":...}}.
     * <p>L'appelant doit fermer l'objet englobant {@code scope*} avec son propre
     * tableau de spans/metrics/logRecords et son éventuel {@code schemaUrl}.</p>
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
