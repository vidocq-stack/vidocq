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
import org.eclipse.microprofile.openapi.models.media.Discriminator;
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
        applyAttributes(base, SchemaAttributes.of(ann));
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
                // The property declared here is applied after the field's own @Schema (if any), so the
                // attributes it sets win and the others of the field are kept.
                applyAttributes(propertySchema, SchemaAttributes.of(property));
                mergedProperties.put(propertyName, propertySchema);
            }
            if (!mergedProperties.isEmpty()) {
                base.setProperties(mergedProperties);
            }
            if (!mergedRequired.isEmpty()) {
                base.setRequired(mergedRequired);
            }
        }
        return base;
    }

    /**
     * The one mapping of the attributes shared by {@code @Schema} and {@code @SchemaProperty}
     * (BUG-20261004-07): each attribute left at its default leaves {@code base} untouched.
     */
    private void applyAttributes(Schema base, SchemaAttributes ann) {
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
        if (ann.examples().length > 0) base.setExamples(List.of(ann.examples()));
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
        if (ann.contentSchema() != Void.class) base.setContentSchema(schemaOfClass(ann.contentSchema()));
        if (!ann.constValue().isEmpty()) base.setConstValue(constValueOf(base, ann.constValue()));
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
        if (ann.enumeration().length > 0) {
            List<Object> values = new ArrayList<>(ann.enumeration().length);
            for (String v : ann.enumeration()) values.add(v);
            base.setEnumeration(values);
        }
        if (ann.allOf().length > 0) base.setAllOf(schemasOfClasses(ann.allOf()));
        if (ann.anyOf().length > 0) base.setAnyOf(schemasOfClasses(ann.anyOf()));
        if (ann.oneOf().length > 0) base.setOneOf(schemasOfClasses(ann.oneOf()));
        if (ann.not() != Void.class) base.setNot(schemaOfClass(ann.not()));
        applyDiscriminator(base, ann);
        if (ann.ifSchema() != Void.class) base.setIfSchema(schemaOfClass(ann.ifSchema()));
        if (ann.thenSchema() != Void.class) base.setThenSchema(schemaOfClass(ann.thenSchema()));
        if (ann.elseSchema() != Void.class) base.setElseSchema(schemaOfClass(ann.elseSchema()));
        if (ann.contains() != Void.class) base.setContains(schemaOfClass(ann.contains()));
        if (ann.minContains() > 0) base.setMinContains(ann.minContains());
        if (ann.maxContains() != Integer.MAX_VALUE) base.setMaxContains(ann.maxContains());
        if (ann.prefixItems().length > 0) base.setPrefixItems(schemasOfClasses(ann.prefixItems()));
        if (ann.propertyNames() != Void.class) base.setPropertyNames(schemaOfClass(ann.propertyNames()));
        if (ann.patternProperties().length > 0) {
            Map<String, Schema> patternProperties = base.getPatternProperties() == null
                    ? new LinkedHashMap<>()
                    : new LinkedHashMap<>(base.getPatternProperties());
            for (var pp : ann.patternProperties()) {
                if (pp.regex().isEmpty() || pp.schema() == Void.class) continue;
                patternProperties.put(pp.regex(), schemaOfClass(pp.schema()));
            }
            if (!patternProperties.isEmpty()) {
                base.setPatternProperties(patternProperties);
            }
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
                dependentSchemas.put(ds.name(), schemaOfClass(ds.schema()));
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
    }

    private void applyDiscriminator(Schema base, SchemaAttributes ann) {
        if (ann.discriminatorProperty().isEmpty() && ann.discriminatorMapping().length == 0) {
            return;
        }
        Discriminator discriminator = base.getDiscriminator() == null
                ? OASFactory.createObject(Discriminator.class)
                : base.getDiscriminator();
        if (!ann.discriminatorProperty().isEmpty()) {
            discriminator.setPropertyName(ann.discriminatorProperty());
        }
        for (var mapping : ann.discriminatorMapping()) {
            if (mapping.value().isEmpty() || mapping.schema() == Void.class) {
                continue;
            }
            // A discriminator mapping value is a schema reference: a target that generates inline
            // (a scalar or any schema without a $ref) has nothing to point to, so it is dropped.
            Schema target = schemaOfClass(mapping.schema());
            if (target != null && target.getRef() != null) {
                discriminator.addMapping(mapping.value(), target.getRef());
            }
        }
        base.setDiscriminator(discriminator);
    }

    private List<Schema> schemasOfClasses(Class<?>[] classes) {
        List<Schema> schemas = new ArrayList<>(classes.length);
        for (Class<?> c : classes) {
            schemas.add(schemaOfClass(c));
        }
        return schemas;
    }

    /** A class named by an annotation attribute, with {@code Schema.True}/{@code False} as boolean schemas. */
    private Schema schemaOfClass(Class<?> schemaImpl) {
        if (schemaImpl == org.eclipse.microprofile.openapi.annotations.media.Schema.True.class) {
            Schema schema = OASFactory.createObject(Schema.class);
            schema.setBooleanSchema(Boolean.TRUE);
            return schema;
        }
        if (schemaImpl == org.eclipse.microprofile.openapi.annotations.media.Schema.False.class) {
            Schema schema = OASFactory.createObject(Schema.class);
            schema.setBooleanSchema(Boolean.FALSE);
            return schema;
        }
        return generate(schemaImpl);
    }

    /**
     * §3.10: {@code constValue} "is parsed as JSON if the schema type is anything other than
     * STRING" — with the parser of extension values, so objects and arrays too.
     */
    private static Object constValueOf(Schema schema, String rawValue) {
        List<Schema.SchemaType> types = schema.getType();
        return types != null && types.contains(Schema.SchemaType.STRING)
                ? rawValue
                : AnnotationModelMappings.parseJsonValue(rawValue);
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

