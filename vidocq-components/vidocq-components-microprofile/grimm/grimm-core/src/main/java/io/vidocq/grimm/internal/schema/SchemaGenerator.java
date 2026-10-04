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
package io.vidocq.grimm.internal.schema;

import io.vidocq.grimm.internal.scanner.AnnotationModelMappings;
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
        // Site-level @Schema(implementation=Schema.True/False.class) → JSON Schema boolean form.
        if (site != null && site.implementation() == org.eclipse.microprofile.openapi.annotations.media.Schema.True.class) {
            Schema bool = OASFactory.createObject(Schema.class);
            bool.setBooleanSchema(Boolean.TRUE);
            return bool;
        }
        if (site != null && site.implementation() == org.eclipse.microprofile.openapi.annotations.media.Schema.False.class) {
            Schema bool = OASFactory.createObject(Schema.class);
            bool.setBooleanSchema(Boolean.FALSE);
            return bool;
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

        // Config overrides (mp.openapi.schema.<FQCN>) are pre-registered in the registry and
        // must win even for scalar JVM types such as java.time.Instant.
        String preRegistered = registry.nameOf(clazz);
        if (preRegistered != null) {
            return registry.buildRef(preRegistered);
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
        var enumAnn = enumClass.getAnnotation(
                org.eclipse.microprofile.openapi.annotations.media.Schema.class);
        String preferred = (enumAnn != null && !enumAnn.name().isEmpty())
                ? enumAnn.name() : simpleName(enumClass);
        String name = registry.reserve(enumClass, preferred);
        Schema body = OASFactory.createObject(Schema.class);
        body.addType(Schema.SchemaType.STRING);
        List<Object> values = new ArrayList<>();
        if (enumAnn != null && enumAnn.enumeration().length > 0) {
            for (String v : enumAnn.enumeration()) values.add(v);
        } else {
            Object[] constants = enumClass.getEnumConstants();
            if (constants != null) {
                for (Object c : constants) values.add(((Enum<?>) c).name());
            }
        }
        if (!values.isEmpty()) {
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
                BeanValidationMapper.apply(propSchema, f.getDeclaredAnnotations());
                properties.put(name, propSchema);
                if ((fieldAnn != null && fieldAnn.required()) || BeanValidationMapper.hasNotNull(f.getDeclaredAnnotations())) {
                    addRequired(required, name);
                }
            }
            // Also pick up bean-style getters not backed by a field.
            for (Method m : c.getDeclaredMethods()) {
                if (!isGetter(m)) {
                    continue;
                }
                String derivedPropertyName = propertyNameOf(m);
                if (derivedPropertyName == null) {
                    continue;
                }
                var ann = m.getAnnotation(
                        org.eclipse.microprofile.openapi.annotations.media.Schema.class);
                if (ann != null && ann.hidden()) {
                    continue;
                }
                Field backingField = findFieldInHierarchy(c, derivedPropertyName);
                if (backingField != null) {
                    var fieldSchema = backingField.getAnnotation(org.eclipse.microprofile.openapi.annotations.media.Schema.class);
                    if (fieldSchema != null && fieldSchema.hidden()) {
                        continue;
                    }
                }
                String prop = (ann != null && !ann.name().isEmpty()) ? ann.name() : derivedPropertyName;
                Schema existing = properties.get(prop);
                if (existing != null) {
                    if (ann != null) {
                        properties.put(prop, applyAnnotationOverrides(existing, ann));
                    }
                    BeanValidationMapper.apply(properties.get(prop), m.getDeclaredAnnotations());
                } else {
                    Schema propSchema = generate(m.getGenericReturnType(), ann);
                    BeanValidationMapper.apply(propSchema, m.getDeclaredAnnotations());
                    properties.put(prop, propSchema);
                }
                if ((ann != null && ann.required()) || BeanValidationMapper.hasNotNull(m.getDeclaredAnnotations())) {
                    addRequired(required, prop);
                }
            }
        }
    }

    private void addRequired(List<String> required, String propertyName) {
        if (!required.contains(propertyName)) {
            required.add(propertyName);
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

    private static Field findFieldInHierarchy(Class<?> type, String fieldName) {
        Class<?> current = type;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    // ------------------ @Schema overrides ------------------

    private Schema applyAnnotationOverrides(Schema base,
            org.eclipse.microprofile.openapi.annotations.media.Schema ann) {
        if (base == null) {
            base = OASFactory.createObject(Schema.class);
        }
        if (ann.type() != SchemaType.DEFAULT) {
            if (ann.type() == SchemaType.ARRAY && base.getRef() != null && base.getItems() == null) {
                Schema itemRef = OASFactory.createObject(Schema.class);
                itemRef.setRef(base.getRef());
                base.setRef(null);
                base.setItems(itemRef);
            }
            base.setType(new ArrayList<>(List.of(mapAnnotationType(ann.type()))));
        }
        if (!ann.format().isEmpty()) base.setFormat(ann.format());
        if (!ann.title().isEmpty()) base.setTitle(ann.title());
        if (!ann.description().isEmpty()) base.setDescription(ann.description());
        if (!ann.comment().isEmpty()) base.setComment(ann.comment());
        if (!ann.pattern().isEmpty()) base.setPattern(ann.pattern());
        if (!ann.example().isEmpty()) {
            base.setExample(ann.example());
            base.setExamples(List.of(ann.example()));
        }
        if (!ann.defaultValue().isEmpty()) base.setDefaultValue(ann.defaultValue());
        if (ann.minLength() > 0) base.setMinLength(ann.minLength());
        if (ann.maxLength() != Integer.MAX_VALUE) base.setMaxLength(ann.maxLength());
        if (ann.minProperties() > 0) base.setMinProperties(ann.minProperties());
        if (ann.maxProperties() != Integer.MAX_VALUE) base.setMaxProperties(ann.maxProperties());
        if (ann.minItems() > 0) base.setMinItems(ann.minItems());
        if (ann.maxItems() != Integer.MAX_VALUE) base.setMaxItems(ann.maxItems());
        if (ann.uniqueItems()) base.setUniqueItems(Boolean.TRUE);
        BigDecimal minimum = ann.minimum().isEmpty() ? null : new BigDecimal(ann.minimum());
        BigDecimal maximum = ann.maximum().isEmpty() ? null : new BigDecimal(ann.maximum());
        if (minimum != null) base.setMinimum(minimum);
        if (maximum != null) base.setMaximum(maximum);
        if (ann.exclusiveMinimum() && minimum != null) base.setExclusiveMinimum(minimum);
        if (ann.exclusiveMaximum() && maximum != null) base.setExclusiveMaximum(maximum);
        if (ann.multipleOf() != 0d) base.setMultipleOf(BigDecimal.valueOf(ann.multipleOf()));
        if (ann.deprecated()) base.setDeprecated(true);
        if (ann.readOnly()) base.setReadOnly(true);
        if (ann.writeOnly()) base.setWriteOnly(true);
        if (ann.nullable()) {
            List<Schema.SchemaType> types = base.getType() == null
                    ? new ArrayList<>()
                    : new ArrayList<>(base.getType());
            if (!types.contains(Schema.SchemaType.NULL)) {
                types.add(Schema.SchemaType.NULL);
            }
            base.setType(types);
        }
        if (!ann.contentEncoding().isEmpty()) base.setContentEncoding(ann.contentEncoding());
        if (!ann.contentMediaType().isEmpty()) base.setContentMediaType(ann.contentMediaType());
        if (!ann.constValue().isEmpty()) base.setConstValue(parseScalar(ann.constValue()));
        if (ann.requiredProperties().length > 0) {
            List<String> mergedRequired = base.getRequired() == null
                    ? new ArrayList<>()
                    : new ArrayList<>(base.getRequired());
            for (String property : ann.requiredProperties()) {
                if (!property.isEmpty() && !mergedRequired.contains(property)) {
                    mergedRequired.add(property);
                }
            }
            if (!mergedRequired.isEmpty()) {
                base.setRequired(mergedRequired);
            }
        }
        if (ann.properties().length > 0) {
            Map<String, Schema> mergedProperties = base.getProperties() == null
                    ? new LinkedHashMap<>()
                    : new LinkedHashMap<>(base.getProperties());
            List<String> mergedRequired = base.getRequired() == null
                    ? new ArrayList<>()
                    : new ArrayList<>(base.getRequired());
            for (org.eclipse.microprofile.openapi.annotations.media.SchemaProperty property : ann.properties()) {
                String propertyName = property.name();
                if (propertyName.isEmpty()) {
                    continue;
                }
                if (property.hidden()) {
                    mergedProperties.remove(propertyName);
                    mergedRequired.remove(propertyName);
                    continue;
                }
                Schema propertySchema = mergedProperties.get(propertyName);
                if (propertySchema == null) {
                    Type baseType = property.implementation() != Void.class ? property.implementation() : Object.class;
                    propertySchema = generate(baseType);
                }
                if (!property.ref().isEmpty()) {
                    propertySchema.setRef(property.ref());
                }
                if (property.type() != SchemaType.DEFAULT) {
                    propertySchema.setType(new ArrayList<>(List.of(mapAnnotationType(property.type()))));
                }
                if (!property.title().isEmpty()) {
                    propertySchema.setTitle(property.title());
                }
                if (!property.description().isEmpty()) {
                    propertySchema.setDescription(property.description());
                }
                if (!property.format().isEmpty()) {
                    propertySchema.setFormat(property.format());
                }
                if (!property.example().isEmpty()) {
                    propertySchema.setExample(property.example());
                    propertySchema.setExamples(List.of(property.example()));
                }
                if (!property.comment().isEmpty()) {
                    propertySchema.setComment(property.comment());
                }
                AnnotationModelMappings.applyExtensions(propertySchema, property.extensions());
                mergedProperties.put(propertyName, propertySchema);
            }
            if (!mergedProperties.isEmpty()) {
                base.setProperties(mergedProperties);
            }
            if (!mergedRequired.isEmpty()) {
                base.setRequired(mergedRequired);
            }
        }
        if (ann.enumeration().length > 0) {
            List<Object> values = new ArrayList<>(ann.enumeration().length);
            for (String v : ann.enumeration()) values.add(v);
            base.setEnumeration(values);
        }
        if (ann.dependentRequired().length > 0) {
            Map<String, List<String>> dependentRequired = base.getDependentRequired() == null
                    ? new LinkedHashMap<>()
                    : new LinkedHashMap<>(base.getDependentRequired());
            for (var dr : ann.dependentRequired()) {
                if (dr.name().isEmpty()) continue;
                dependentRequired.put(dr.name(), new ArrayList<>(List.of(dr.requires())));
            }
            if (!dependentRequired.isEmpty()) {
                base.setDependentRequired(dependentRequired);
            }
        }
        if (ann.dependentSchemas().length > 0) {
            Map<String, Schema> dependentSchemas = base.getDependentSchemas() == null
                    ? new LinkedHashMap<>()
                    : new LinkedHashMap<>(base.getDependentSchemas());
            for (var ds : ann.dependentSchemas()) {
                if (ds.name().isEmpty()) continue;
                Class<?> schemaImpl = ds.schema();
                Schema schema;
                if (schemaImpl == org.eclipse.microprofile.openapi.annotations.media.Schema.True.class) {
                    schema = OASFactory.createObject(Schema.class);
                    schema.setBooleanSchema(Boolean.TRUE);
                } else if (schemaImpl == org.eclipse.microprofile.openapi.annotations.media.Schema.False.class) {
                    schema = OASFactory.createObject(Schema.class);
                    schema.setBooleanSchema(Boolean.FALSE);
                } else {
                    schema = generate(schemaImpl);
                }
                dependentSchemas.put(ds.name(), schema);
            }
            if (!dependentSchemas.isEmpty()) {
                base.setDependentSchemas(dependentSchemas);
            }
        }
        Class<?> additionalProperties = ann.additionalProperties();
        if (additionalProperties == org.eclipse.microprofile.openapi.annotations.media.Schema.True.class) {
            base.setAdditionalPropertiesBoolean(Boolean.TRUE);
        } else if (additionalProperties == org.eclipse.microprofile.openapi.annotations.media.Schema.False.class) {
            base.setAdditionalPropertiesBoolean(Boolean.FALSE);
        } else if (additionalProperties != Void.class) {
            base.setAdditionalPropertiesSchema(generate(additionalProperties));
        }
        if (!ann.externalDocs().url().isEmpty()) {
            base.setExternalDocs(AnnotationModelMappings.toModelExternalDocs(ann.externalDocs()));
        }
        AnnotationModelMappings.applyExtensions(base, ann.extensions());
        return base;
    }

    private static Object parseScalar(String rawValue) {
        if ("true".equalsIgnoreCase(rawValue) || "false".equalsIgnoreCase(rawValue)) {
            return Boolean.parseBoolean(rawValue);
        }
        try {
            if (rawValue.contains(".")) {
                return Double.parseDouble(rawValue);
            }
            return Long.parseLong(rawValue);
        } catch (NumberFormatException ignored) {
            return rawValue;
        }
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

