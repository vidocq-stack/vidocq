package io.vidocq.grimm.internal.config;

import io.vidocq.grimm.internal.schema.SchemaRegistry;
import io.vidocq.grimm.internal.serialization.JsonDeserializer;
import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.media.Schema;
import org.eclipse.microprofile.openapi.models.servers.Server;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies {@link GrimmConfig} side effects to the assembled OpenAPI model
 * (spec §4.1 keys {@code mp.openapi.servers} and {@code mp.openapi.schema.<FQCN>}).
 *
 * <p>Called after the {@code ModelMerger} but before the {@code FilterInvoker} so
 * filters can still inspect / mutate the config-applied state.</p>
 */
public final class ConfigApplier {

    private ConfigApplier() {}

    /**
     * Replaces {@code openAPI.servers} with the URLs from {@code mp.openapi.servers}
     * when the property is set (non-empty). When unset, the model's existing servers
     * are left untouched.
     */
    public static void applyServers(OpenAPI openAPI, GrimmConfig config) {
        if (openAPI == null || config == null || config.servers().isEmpty()) {
            return;
        }
        List<Server> servers = new ArrayList<>();
        for (String url : config.servers()) {
            Server s = OASFactory.createObject(Server.class);
            s.setUrl(url);
            servers.add(s);
        }
        openAPI.setServers(servers);
    }

    /**
     * Registers schema overrides from {@code mp.openapi.schema.<FQCN>} into the given
     * {@link SchemaRegistry} under the simple class name. Subsequent calls to
     * {@code SchemaGenerator.generate(clazz)} for one of these classes will return a
     * {@code $ref} to the override.
     *
     * <p>The value of each property is parsed as a JSON object via {@link JsonDeserializer}
     * and converted into a {@link Schema}.</p>
     *
     * @return a map FQCN → registered schema name (for diagnostic purposes)
     */
    public static Map<String, String> applySchemaOverrides(SchemaRegistry registry, GrimmConfig config) {
        if (registry == null || config == null || config.schemaOverrides().isEmpty()) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : config.schemaOverrides().entrySet()) {
            String fqcn = e.getKey();
            String json = e.getValue();
            Class<?> clazz = tryLoad(fqcn);
            if (clazz == null) {
                continue; // unknown class — silently skip (spec is silent on this case)
            }
            Object parsed = JsonDeserializer.parseRaw(json);
            if (!(parsed instanceof Map<?, ?> map)) {
                continue; // not a JSON object — skip
            }
            Schema schema = toSchema(map);
            String name = registry.reserve(clazz, clazz.getSimpleName());
            registry.publish(name, schema);
            result.put(fqcn, name);
        }
        return result;
    }

    private static Class<?> tryLoad(String fqcn) {
        try {
            return Class.forName(fqcn, false, Thread.currentThread().getContextClassLoader());
        } catch (ClassNotFoundException e) {
            try {
                return Class.forName(fqcn);
            } catch (ClassNotFoundException ignored) {
                return null;
            }
        }
    }

    /** Minimal JSON-tree → {@link Schema} converter — covers the common Schema fields. */
    @SuppressWarnings("unchecked")
    static Schema toSchema(Map<?, ?> map) {
        Schema schema = OASFactory.createObject(Schema.class);
        for (Map.Entry<?, ?> e : map.entrySet()) {
            String key = String.valueOf(e.getKey());
            Object value = e.getValue();
            switch (key) {
                case "type" -> setType(schema, value);
                case "format" -> schema.setFormat(asString(value));
                case "title" -> schema.setTitle(asString(value));
                case "description" -> schema.setDescription(asString(value));
                case "pattern" -> schema.setPattern(asString(value));
                case "$ref", "ref" -> schema.setRef(asString(value));
                case "minLength" -> schema.setMinLength(asInt(value));
                case "maxLength" -> schema.setMaxLength(asInt(value));
                case "minimum" -> schema.setMinimum(asDecimal(value));
                case "maximum" -> schema.setMaximum(asDecimal(value));
                case "multipleOf" -> schema.setMultipleOf(asDecimal(value));
                case "default" -> schema.setDefaultValue(value);
                case "deprecated" -> schema.setDeprecated(asBool(value));
                case "readOnly" -> schema.setReadOnly(asBool(value));
                case "writeOnly" -> schema.setWriteOnly(asBool(value));
                case "required" -> {
                    if (value instanceof List<?> list) {
                        List<String> req = new ArrayList<>();
                        for (Object o : list) req.add(String.valueOf(o));
                        schema.setRequired(req);
                    }
                }
                case "enum" -> {
                    if (value instanceof List<?> list) {
                        schema.setEnumeration(new ArrayList<>((List<Object>) list));
                    }
                }
                case "items" -> {
                    if (value instanceof Map<?, ?> m) schema.setItems(toSchema(m));
                }
                case "properties" -> {
                    if (value instanceof Map<?, ?> m) {
                        Map<String, Schema> props = new LinkedHashMap<>();
                        for (Map.Entry<?, ?> p : m.entrySet()) {
                            if (p.getValue() instanceof Map<?, ?> pm) {
                                props.put(String.valueOf(p.getKey()), toSchema(pm));
                            }
                        }
                        schema.setProperties(props);
                    }
                }
                case "additionalProperties" -> {
                    if (value instanceof Map<?, ?> m) {
                        schema.setAdditionalPropertiesSchema(toSchema(m));
                    } else if (value instanceof Boolean b) {
                        schema.setAdditionalPropertiesBoolean(b);
                    }
                }
                default -> {
                    if (key.startsWith("x-")) schema.addExtension(key, value);
                }
            }
        }
        return schema;
    }

    private static void setType(Schema schema, Object value) {
        if (value instanceof String s) {
            schema.addType(parseType(s));
        } else if (value instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof String s) schema.addType(parseType(s));
            }
        }
    }

    private static Schema.SchemaType parseType(String s) {
        return switch (s.toLowerCase()) {
            case "integer" -> Schema.SchemaType.INTEGER;
            case "number" -> Schema.SchemaType.NUMBER;
            case "boolean" -> Schema.SchemaType.BOOLEAN;
            case "string" -> Schema.SchemaType.STRING;
            case "array" -> Schema.SchemaType.ARRAY;
            case "null" -> Schema.SchemaType.NULL;
            default -> Schema.SchemaType.OBJECT;
        };
    }

    private static String asString(Object o) { return o == null ? null : String.valueOf(o); }
    private static Boolean asBool(Object o) { return o instanceof Boolean b ? b : Boolean.valueOf(String.valueOf(o)); }
    private static Integer asInt(Object o) {
        if (o instanceof Number n) return n.intValue();
        if (o == null) return null;
        return Integer.valueOf(o.toString());
    }
    private static BigDecimal asDecimal(Object o) {
        if (o == null) return null;
        if (o instanceof BigDecimal bd) return bd;
        if (o instanceof Number n) return new BigDecimal(n.toString());
        return new BigDecimal(o.toString());
    }
}

