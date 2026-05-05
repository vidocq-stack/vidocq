package io.vidocq.mansart.data.core;

import io.vidocq.mansart.data.dialect.Attribute;
import io.vidocq.mansart.data.dialect.EntityModel;
import io.vidocq.mansart.data.dialect.SqlNames;
import io.vidocq.mansart.data.dialect.attribute.EnumAttribute;
import io.vidocq.mansart.data.dialect.attribute.IdAttribute;
import io.vidocq.mansart.data.dialect.attribute.NumericAttribute;
import io.vidocq.mansart.data.dialect.attribute.ReferenceAttribute;
import io.vidocq.mansart.data.dialect.attribute.TemporalAttribute;
import io.vidocq.mansart.data.dialect.attribute.TextAttribute;
import io.vidocq.mansart.data.dialect.attribute.VersionAttribute;

import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Runtime equivalent of {@code mansart-data-processor.EntityScanner} +
 * {@code MansartMetamodelWriter}: builds an {@link EntityModel} for an entity class via
 * reflection, supporting both Mansart and JPA mapping annotations. Used by the M7 runtime
 * fallback path when a {@code @Repository} interface is discovered for which no compile-time
 * {@code _Entity} metamodel exists (typically: TCK entities, ad-hoc usages without APT).
 */
public final class RuntimeEntityModelBuilder {

    private RuntimeEntityModelBuilder() {}

    public static <E> EntityModel<E> build(Class<E> entityClass) {
        return build(entityClass, null);
    }

    /**
     * M7-5 — Jakarta Data 1.0 supports entities with no explicit {@code @Id} annotation: the
     * field named {@code id} (or matching the K type from {@code BasicRepository<E, K>}) is the
     * primary key by convention. If {@code idTypeHint} is non-null and no annotated id is found,
     * a field named {@code id} (or the single field of that type) is used as the implicit id.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static <E> EntityModel<E> build(Class<E> entityClass, Class<?> idTypeHint) {
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(
                    entityClass, MethodHandles.lookup());
            MethodHandle ctor = lookup.findConstructor(entityClass, MethodType.methodType(void.class));

            String tableName = readTableName(entityClass);
            String schema    = readSchema(entityClass);

            List<Field> persistedFields = new ArrayList<>();
            for (Field field : entityClass.getDeclaredFields()) {
                int mods = field.getModifiers();
                if (Modifier.isStatic(mods) || Modifier.isTransient(mods)) continue;
                if (hasAnnotation(field, "jakarta.persistence.Transient")) continue;
                if (hasAnnotation(field, "io.vidocq.mansart.data.Transient")) continue;
                persistedFields.add(field);
            }

            // M7-5 — pick the implicit id field BEFORE iterating attributes so describeField
            // sees a stable choice. Order: explicit annotation > field named "id" > single
            // field whose type matches the K hint > field named "<entitySimple>Id".
            Field implicitIdField = pickImplicitIdField(entityClass, persistedFields, idTypeHint);

            List<Attribute<E, ?>> attrs = new ArrayList<>();
            IdAttribute<E, ?> id = null;
            VersionAttribute<E, ?> version = null;

            for (Field field : persistedFields) {
                boolean implicitId = field == implicitIdField;
                Attribute<E, ?> a = describeField(entityClass, field, lookup, implicitId);
                attrs.add(a);
                if (a instanceof IdAttribute<?, ?>      ida) id      = (IdAttribute<E, ?>) ida;
                if (a instanceof VersionAttribute<?, ?> v)   version = (VersionAttribute<E, ?>) v;
            }

            if (id == null) {
                throw new MansartDataException("Entity " + entityClass.getName()
                        + " has no @Id field (and no implicit id field matched idTypeHint="
                        + (idTypeHint == null ? "<none>" : idTypeHint.getName()) + ")");
            }

            return new EntityModel<>(entityClass, tableName, schema, id,
                    Optional.ofNullable(version), attrs, ctor);
        } catch (NoSuchMethodException e) {
            throw new MansartDataException(entityClass.getName()
                    + " must declare a no-arg constructor (Mansart/JPA convention).", e);
        } catch (IllegalAccessException e) {
            throw new MansartDataException("Cannot privateLookupIn " + entityClass.getName()
                    + " — open the entity package to mansart-data-core via `opens` in your module-info.", e);
        }
    }

    private static boolean isExplicitId(Field field) {
        return hasAnnotation(field, "jakarta.persistence.Id")
            || hasAnnotation(field, "io.vidocq.mansart.data.Id");
    }

    /**
     * Jakarta Data 1.0 implicit-id resolution. Returns the field that should be treated as the
     * id when no field carries an explicit {@code @Id}. Otherwise null (an explicit one will be
     * picked up by {@code describeField}).
     */
    private static Field pickImplicitIdField(Class<?> entityClass, List<Field> persisted, Class<?> idTypeHint) {
        for (Field f : persisted) if (isExplicitId(f)) return null;

        for (Field f : persisted) {
            if ("id".equals(f.getName()) && (idTypeHint == null || matchesType(f, idTypeHint))) return f;
        }

        if (idTypeHint != null) {
            Field unique = null;
            int matchCount = 0;
            for (Field f : persisted) {
                if (matchesType(f, idTypeHint)) { unique = f; matchCount++; }
            }
            if (matchCount == 1) return unique;
        }

        String entityName = entityClass.getSimpleName();
        if (!entityName.isEmpty()) {
            String suffix = Character.toLowerCase(entityName.charAt(0)) + entityName.substring(1) + "Id";
            for (Field f : persisted) {
                if (suffix.equals(f.getName())
                        && (idTypeHint == null || matchesType(f, idTypeHint))) return f;
            }
        }
        return null;
    }

    private static boolean matchesType(Field f, Class<?> hint) {
        Class<?> ft = boxed(f.getType());
        Class<?> hb = boxed(hint);
        return hb.isAssignableFrom(ft) || ft.isAssignableFrom(hb);
    }

    /* ---- field → Attribute mapping ---- */

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <E> Attribute<E, ?> describeField(Class<E> entityClass, Field field,
                                                     MethodHandles.Lookup lookup,
                                                     boolean implicitId) {
        String name = field.getName();
        Class<?> type = boxed(field.getType());

        boolean isId       = implicitId
                          || hasAnnotation(field, "jakarta.persistence.Id")
                          || hasAnnotation(field, "io.vidocq.mansart.data.Id");
        boolean isVersion  = hasAnnotation(field, "jakarta.persistence.Version")
                          || hasAnnotation(field, "io.vidocq.mansart.data.Version");
        boolean isManyToOne = hasAnnotation(field, "jakarta.persistence.ManyToOne")
                          || hasAnnotation(field, "io.vidocq.mansart.data.ManyToOne");
        boolean isOneToOne  = hasAnnotation(field, "jakarta.persistence.OneToOne")
                          || hasAnnotation(field, "io.vidocq.mansart.data.OneToOne");
        boolean isReference = isManyToOne || isOneToOne;
        boolean generated  = hasAnnotation(field, "jakarta.persistence.GeneratedValue")
                          || hasAnnotation(field, "io.vidocq.mansart.data.GeneratedValue");

        String columnName = readColumnName(field, isReference);
        boolean nullable  = readBooleanColumnAttr(field, "nullable", true);
        boolean unique    = readBooleanColumnAttr(field, "unique",   false);
        int     length    = (int) readIntColumnAttr(field, "length", 255);

        MethodHandle getter, setter;
        try {
            getter = lookup.findGetter(entityClass, name, field.getType());
            setter = lookup.findSetter(entityClass, name, field.getType());
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new MansartDataException("Cannot create MethodHandle for " + entityClass.getName()
                    + "#" + name, e);
        }

        if (isId) {
            return new IdAttribute<>(name, columnName, type, entityClass, generated, getter, setter);
        }
        if (isVersion) {
            return new VersionAttribute(name, columnName, type, entityClass, getter, setter);
        }
        if (isReference) {
            return new ReferenceAttribute<>(name, columnName, type, entityClass,
                    nullable, unique, false, getter, setter);
        }
        if (type.isEnum()) {
            return new EnumAttribute(name, columnName, type, entityClass,
                    nullable, unique, io.vidocq.mansart.data.EnumType.ORDINAL, getter, setter);
        }
        if (type == String.class) {
            return new TextAttribute<>(name, columnName, entityClass, nullable, unique, length, getter, setter);
        }
        if (isTemporal(type)) {
            return new TemporalAttribute<>(name, columnName, type, entityClass, nullable, unique, getter, setter);
        }
        if (isNumeric(type)) {
            return new NumericAttribute(name, columnName, type, entityClass,
                    nullable, unique, 0, 0, getter, setter);
        }
        // Fallback — let the dialect figure it out via Types.OTHER
        return new NumericAttribute(name, columnName, type, entityClass,
                nullable, unique, 0, 0, getter, setter);
    }

    /* ---- annotation reading via reflection-by-name (no compile dep on JPA) ---- */

    private static boolean hasAnnotation(java.lang.reflect.AnnotatedElement el, String fqn) {
        for (Annotation a : el.getDeclaredAnnotations()) {
            if (a.annotationType().getName().equals(fqn)) return true;
        }
        return false;
    }

    private static <T> T readAnnoMember(java.lang.reflect.AnnotatedElement el, String annoFqn,
                                        String member, Class<T> type, T defaultValue) {
        for (Annotation a : el.getDeclaredAnnotations()) {
            if (!a.annotationType().getName().equals(annoFqn)) continue;
            try {
                Method m = a.annotationType().getMethod(member);
                Object v = m.invoke(a);
                if (v == null) return defaultValue;
                if (type.isInstance(v)) return type.cast(v);
                return defaultValue;
            } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private static String readTableName(Class<?> entityClass) {
        String name = readAnnoMember(entityClass, "jakarta.persistence.Table",
                "name", String.class, "");
        if (name.isEmpty()) {
            name = readAnnoMember(entityClass, "io.vidocq.mansart.data.Table",
                    "name", String.class, "");
        }
        if (name.isEmpty()) name = SqlNames.tableName(entityClass.getSimpleName());
        return name;
    }

    private static String readSchema(Class<?> entityClass) {
        String s = readAnnoMember(entityClass, "jakarta.persistence.Table",
                "schema", String.class, "");
        if (s.isEmpty()) {
            s = readAnnoMember(entityClass, "io.vidocq.mansart.data.Table",
                    "schema", String.class, "");
        }
        return s;
    }

    private static String readColumnName(Field field, boolean isReference) {
        String n = readAnnoMember(field, "jakarta.persistence.Column", "name", String.class, "");
        if (n.isEmpty()) n = readAnnoMember(field, "io.vidocq.mansart.data.Column", "name", String.class, "");
        if (n.isEmpty()) n = readAnnoMember(field, "jakarta.persistence.JoinColumn", "name", String.class, "");
        if (n.isEmpty()) n = readAnnoMember(field, "io.vidocq.mansart.data.JoinColumn", "name", String.class, "");
        if (n.isEmpty()) {
            n = isReference
                    ? SqlNames.foreignKeyColumn(field.getName())
                    : SqlNames.columnName(field.getName());
        }
        return n;
    }

    private static boolean readBooleanColumnAttr(Field field, String member, boolean def) {
        Boolean b = readAnnoMember(field, "jakarta.persistence.Column", member, Boolean.class, null);
        if (b != null) return b;
        b = readAnnoMember(field, "io.vidocq.mansart.data.Column", member, Boolean.class, def);
        return b == null ? def : b;
    }

    private static long readIntColumnAttr(Field field, String member, long def) {
        Integer i = readAnnoMember(field, "jakarta.persistence.Column", member, Integer.class, null);
        if (i != null) return i.longValue();
        i = readAnnoMember(field, "io.vidocq.mansart.data.Column", member, Integer.class, (int) def);
        return i == null ? def : i.longValue();
    }

    private static Class<?> boxed(Class<?> c) {
        if (c == boolean.class) return Boolean.class;
        if (c == byte.class)    return Byte.class;
        if (c == short.class)   return Short.class;
        if (c == int.class)     return Integer.class;
        if (c == long.class)    return Long.class;
        if (c == float.class)   return Float.class;
        if (c == double.class)  return Double.class;
        if (c == char.class)    return Character.class;
        return c;
    }

    private static boolean isTemporal(Class<?> c) {
        return c == LocalDate.class || c == LocalDateTime.class || c == LocalTime.class
                || c == OffsetDateTime.class || c == java.time.Instant.class
                || c == java.util.Date.class || c == java.sql.Date.class;
    }

    private static boolean isNumeric(Class<?> c) {
        return c == Byte.class || c == Short.class || c == Integer.class || c == Long.class
                || c == Float.class || c == Double.class
                || c == BigDecimal.class || c == BigInteger.class;
    }
}
