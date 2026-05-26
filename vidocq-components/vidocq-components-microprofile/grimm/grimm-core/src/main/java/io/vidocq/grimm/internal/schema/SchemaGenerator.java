package io.vidocq.grimm.internal.schema;

import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.models.media.Schema;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Generates {@link Schema} objects from Java {@link Type}s (spec §3.10 / §3.11).
 *
 * <p>Mappings:</p>
 * <ul>
 *   <li>Primitives + wrappers, {@code String}, {@code UUID}, date/time, {@code BigDecimal}/{@code BigInteger}</li>
 *   <li>{@code Collection<T>} / arrays → {@code type:array, items: schemaOf(T)}</li>
 *   <li>{@code Map<String, V>} → {@code type:object, additionalProperties: schemaOf(V)}</li>
 *   <li>Enums → {@code type:string, enum:[…]}</li>
 *   <li>POJOs → {@code type:object, properties:{…}} interned in the {@link SchemaRegistry}
 *       and returned as a {@code $ref}; cycles handled via name reservation.</li>
 *   <li>{@code @Schema} annotation on classes / fields overrides inferred schema</li>
 * </ul>
 *
 * <p>Pure reflection — no {@code setAccessible(true)}, no {@code synchronized}, no {@code Proxy}.</p>
 */
public final class SchemaGenerator {

    private final SchemaRegistry registry;

    public SchemaGenerator(SchemaRegistry registry) {
        this.registry = registry;
    }

    /** Generates a schema for {@code type}. Named (POJO/enum) types are returned as {@code $ref}. */
    public Schema generate(Type type) {
        return generateInternal(type, null);
    }

    /**
     * Generates a schema honouring an explicit {@code @Schema} annotation on the declaration site
     * (parameter, field, return type). Pass {@code null} when no site-level annotation applies.
     */
    public Schema generate(Type type,
                           org.eclipse.microprofile.openapi.annotations.media.Schema siteAnnotation) {
        return generateInternal(type, siteAnnotation);
    }

    // ------------------------------------------------------------------

    private Schema generateInternal(Type type,
                                    org.eclipse.microprofile.openapi.annotations.media.Schema site) {
        // Site-level @Schema(ref=…) wins outright (spec §3.10).
        if (site != null && !site.ref().isEmpty()) {
            Schema ref = OASFactory.createObject(Schema.class);
            ref.setRef(site.ref());
            return ref;
        }
        // Site-level @Schema(implementation=…) overrides the declared type.
        if (site != null && site.implementation() != Void.class) {
            Schema base = generateInternal(site.implementation(), null);
            return applyAnnotationOverrides(base, site);
        }
        Schema inferred = inferSchema(type);
        if (site != null) {
            inferred = applyAnnotationOverrides(inferred, site);
        }
        return inferred;
    }

    private Schema inferSchema(Type type) {
        if (type instanceof Class<?> clazz) {
            return schemaForClass(clazz);
        }
        if (type instanceof ParameterizedType pt) {
            return schemaForParameterized(pt);
        }
        if (type instanceof WildcardType wt) {
            Type[] upper = wt.getUpperBounds();
            if (upper.length > 0) {
                return generate(upper[0]);
            }
            return objectSchema();
        }
        return objectSchema();
    }

    private Schema schemaForClass(Class<?> clazz) {
        if (clazz.isArray()) {
            Schema s = OASFactory.createObject(Schema.class);
            s.addType(Schema.SchemaType.ARRAY);
            s.setItems(generate(clazz.getComponentType()));
            return s;
        }
        if (clazz.isEnum()) {
            return registerEnum(clazz);
        }
        Schema scalar = scalarSchema(clazz);
        if (scalar != null) {
            return scalar;
        }
        // POJO — intern as named component.
        return registerPojo(clazz);
    }

    private Schema schemaForParameterized(ParameterizedType pt) {
        Type raw = pt.getRawType();
        if (!(raw instanceof Class<?> rawClass)) {
            return objectSchema();
        }
        if (Map.class.isAssignableFrom(rawClass)) {
            Schema s = OASFactory.createObject(Schema.class);
            s.addType(Schema.SchemaType.OBJECT);
            Type[] args = pt.getActualTypeArguments();
            if (args.length == 2) {
                s.setAdditionalPropertiesSchema(generate(args[1]));
            }
            return s;
        }
        if (Collection.class.isAssignableFrom(rawClass)) {
            Schema s = OASFactory.createObject(Schema.class);
            s.addType(Schema.SchemaType.ARRAY);
            Type[] args = pt.getActualTypeArguments();
            if (args.length == 1) {
                s.setItems(generate(args[0]));
            }
            return s;
        }
        // Other parameterized types: ignore type arguments, treat as raw POJO.
        return schemaForClass(rawClass);
    }

    // ------------------ Scalars ------------------

    private Schema scalarSchema(Class<?> clazz) {
        if (clazz == String.class || clazz == char.class || clazz == Character.class
                || clazz == CharSequence.class) {
            return stringSchema(null);
        }
        if (clazz == boolean.class || clazz == Boolean.class) {
            Schema s = OASFactory.createObject(Schema.class);
            s.addType(Schema.SchemaType.BOOLEAN);
            return s;
        }
        if (clazz == byte.class || clazz == Byte.class
                || clazz == short.class || clazz == Short.class
                || clazz == int.class || clazz == Integer.class) {
            Schema s = OASFactory.createObject(Schema.class);
            s.addType(Schema.SchemaType.INTEGER);
            s.setFormat("int32");
            return s;
        }
        if (clazz == long.class || clazz == Long.class || clazz == BigInteger.class) {
            Schema s = OASFactory.createObject(Schema.class);
            s.addType(Schema.SchemaType.INTEGER);
            s.setFormat("int64");
            return s;
        }
        if (clazz == float.class || clazz == Float.class) {
            Schema s = OASFactory.createObject(Schema.class);
            s.addType(Schema.SchemaType.NUMBER);
            s.setFormat("float");
            return s;
        }
        if (clazz == double.class || clazz == Double.class) {
            Schema s = OASFactory.createObject(Schema.class);
            s.addType(Schema.SchemaType.NUMBER);
            s.setFormat("double");
            return s;
        }
        if (clazz == BigDecimal.class || clazz == Number.class) {
            Schema s = OASFactory.createObject(Schema.class);
            s.addType(Schema.SchemaType.NUMBER);
            return s;
        }
        if (clazz == UUID.class) {
            return stringSchema("uuid");
        }
        if (clazz == LocalDate.class) {
            return stringSchema("date");
        }
        if (clazz == Date.class || clazz == Instant.class || clazz == LocalDateTime.class
                || clazz == OffsetDateTime.class || clazz == ZonedDateTime.class) {
            return stringSchema("date-time");
        }
        if (clazz == LocalTime.class) {
            return stringSchema("time");
        }
        if (clazz == Object.class) {
            return objectSchema();
        }
        if (clazz == void.class || clazz == Void.class) {
            return null;
        }
        return null;
    }

    private Schema stringSchema(String format) {
        Schema s = OASFactory.createObject(Schema.class);
        s.addType(Schema.SchemaType.STRING);
        if (format != null) {
            s.setFormat(format);
        }
        return s;
    }

    private Schema objectSchema() {
        Schema s = OASFactory.createObject(Schema.class);
        s.addType(Schema.SchemaType.OBJECT);
        return s;
    }

    // ------------------ Enums ------------------

    private Schema registerEnum(Class<?> enumClass) {
        String existing = registry.nameOf(enumClass);
        if (existing != null) {
            return registry.buildRef(existing);
        }
        String name = registry.reserve(enumClass, simpleName(enumClass));
        Schema body = OASFactory.createObject(Schema.class);
        body.addType(Schema.SchemaType.STRING);
        Object[] constants = enumClass.getEnumConstants();
        if (constants != null) {
            List<Object> values = new ArrayList<>(constants.length);
            for (Object c : constants) {
                values.add(((Enum<?>) c).name());
            }
            body.setEnumeration(values);
        }
        registry.publish(name, body);
        return registry.buildRef(name);
    }

    // ------------------ POJOs ------------------

    private Schema registerPojo(Class<?> clazz) {
        // Honour class-level @Schema(hidden=true) by returning a plain object schema with no body.
        var classAnn = clazz.getAnnotation(
                org.eclipse.microprofile.openapi.annotations.media.Schema.class);
        if (classAnn != null && classAnn.hidden()) {
            return objectSchema();
        }
        String preferred = (classAnn != null && !classAnn.name().isEmpty())
                ? classAnn.name() : simpleName(clazz);
        String existing = registry.nameOf(clazz);
        if (existing != null) {
            return registry.buildRef(existing);
        }
        String name = registry.reserve(clazz, preferred);
        Schema body = OASFactory.createObject(Schema.class);
        body.addType(Schema.SchemaType.OBJECT);
        Map<String, Schema> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        collectProperties(clazz, properties, required);
        if (!properties.isEmpty()) {
            body.setProperties(properties);
        }
        if (!required.isEmpty()) {
            body.setRequired(required);
        }
        if (classAnn != null) {
            applyAnnotationOverrides(body, classAnn);
        }
        registry.publish(name, body);
        return registry.buildRef(name);
    }

    private void collectProperties(Class<?> clazz,
                                   Map<String, Schema> properties,
                                   List<String> required) {
        // Walk class hierarchy (skip Object).
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                int mods = f.getModifiers();
                if (Modifier.isStatic(mods) || f.isSynthetic()) {
                    continue;
                }
                var fieldAnn = f.getAnnotation(
                        org.eclipse.microprofile.openapi.annotations.media.Schema.class);
                if (fieldAnn != null && fieldAnn.hidden()) {
                    continue;
                }
                String name = f.getName();
                if (properties.containsKey(name)) {
                    continue;
                }
                Schema propSchema = generate(f.getGenericType(), fieldAnn);
                properties.put(name, propSchema);
                if (fieldAnn != null && fieldAnn.required()) {
                    required.add(name);
                }
            }
            // Also pick up bean-style getters not backed by a field.
            for (Method m : c.getDeclaredMethods()) {
                if (!isGetter(m)) {
                    continue;
                }
                String prop = propertyNameOf(m);
                if (prop == null || properties.containsKey(prop)) {
                    continue;
                }
                var ann = m.getAnnotation(
                        org.eclipse.microprofile.openapi.annotations.media.Schema.class);
                if (ann != null && ann.hidden()) {
                    continue;
                }
                Schema propSchema = generate(m.getGenericReturnType(), ann);
                properties.put(prop, propSchema);
                if (ann != null && ann.required()) {
                    required.add(prop);
                }
            }
        }
    }

    private static boolean isGetter(Method m) {
        int mods = m.getModifiers();
        if (!Modifier.isPublic(mods) || Modifier.isStatic(mods)) return false;
        if (m.getParameterCount() != 0) return false;
        Class<?> declaring = m.getDeclaringClass();
        if (declaring == Object.class) return false;
        String n = m.getName();
        if (n.startsWith("get") && n.length() > 3 && m.getReturnType() != void.class) return true;
        return n.startsWith("is") && n.length() > 2
                && (m.getReturnType() == boolean.class || m.getReturnType() == Boolean.class);
    }

    private static String propertyNameOf(Method m) {
        String n = m.getName();
        String raw;
        if (n.startsWith("get") && n.length() > 3) raw = n.substring(3);
        else if (n.startsWith("is") && n.length() > 2) raw = n.substring(2);
        else return null;
        if (raw.isEmpty()) return null;
        return Character.toLowerCase(raw.charAt(0)) + raw.substring(1);
    }

    private static String simpleName(Class<?> c) {
        String n = c.getSimpleName();
        return n.isEmpty() ? c.getName().replace('.', '_') : n;
    }

    // ------------------ @Schema overrides ------------------

    private Schema applyAnnotationOverrides(Schema base,
            org.eclipse.microprofile.openapi.annotations.media.Schema ann) {
        if (base == null) {
            base = OASFactory.createObject(Schema.class);
        }
        if (ann.type() != SchemaType.DEFAULT) {
            base.setType(new ArrayList<>(List.of(mapAnnotationType(ann.type()))));
        }
        if (!ann.format().isEmpty()) base.setFormat(ann.format());
        if (!ann.title().isEmpty()) base.setTitle(ann.title());
        if (!ann.description().isEmpty()) base.setDescription(ann.description());
        if (!ann.pattern().isEmpty()) base.setPattern(ann.pattern());
        if (!ann.example().isEmpty()) base.setExample(ann.example());
        if (!ann.defaultValue().isEmpty()) base.setDefaultValue(ann.defaultValue());
        if (ann.minLength() > 0) base.setMinLength(ann.minLength());
        if (ann.maxLength() > 0) base.setMaxLength(ann.maxLength());
        if (!ann.minimum().isEmpty()) base.setMinimum(new BigDecimal(ann.minimum()));
        if (!ann.maximum().isEmpty()) base.setMaximum(new BigDecimal(ann.maximum()));
        if (ann.multipleOf() != 0d) base.setMultipleOf(BigDecimal.valueOf(ann.multipleOf()));
        if (ann.deprecated()) base.setDeprecated(true);
        if (ann.readOnly()) base.setReadOnly(true);
        if (ann.writeOnly()) base.setWriteOnly(true);
        if (ann.enumeration().length > 0) {
            List<Object> values = new ArrayList<>(ann.enumeration().length);
            for (String v : ann.enumeration()) values.add(v);
            base.setEnumeration(values);
        }
        return base;
    }

    private static Schema.SchemaType mapAnnotationType(SchemaType t) {
        return switch (t) {
            case INTEGER -> Schema.SchemaType.INTEGER;
            case NUMBER -> Schema.SchemaType.NUMBER;
            case BOOLEAN -> Schema.SchemaType.BOOLEAN;
            case STRING -> Schema.SchemaType.STRING;
            case ARRAY -> Schema.SchemaType.ARRAY;
            case OBJECT -> Schema.SchemaType.OBJECT;
            case DEFAULT -> Schema.SchemaType.OBJECT;
        };
    }

    @SuppressWarnings("unused")
    private static String lower(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }
}

