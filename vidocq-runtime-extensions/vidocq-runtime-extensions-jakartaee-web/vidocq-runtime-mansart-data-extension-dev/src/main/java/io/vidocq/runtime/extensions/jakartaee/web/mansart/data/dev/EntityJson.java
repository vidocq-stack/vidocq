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
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev;

import io.vidocq.mansart.data.dialect.Attribute;
import io.vidocq.mansart.data.dialect.EntityModel;
import io.vidocq.mansart.data.dialect.attribute.IdAttribute;
import io.vidocq.mansart.data.dialect.attribute.JoinedAttribute;
import io.vidocq.mansart.data.dialect.attribute.ReferenceAttribute;
import io.vidocq.mansart.data.dialect.attribute.VersionAttribute;

import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Entities to and from JSON through the model Mansart itself uses, {@code EntityModels.of} (spec §5): read with each
 * attribute's getter handle, built with the no-arg constructor handle then each property present set with its setter
 * handle. A property per attribute that has a setter and a type of spec §4, in model order; a reference to another
 * entity is that entity's id, never its graph; a joined attribute is left out.
 */
final class EntityJson {

    private final Function<Class<?>, EntityModel<?>> models;
    private final Set<String> entities;

    /**
     * @param models           the model of an entity class, {@code EntityModels.of} outside tests
     * @param entityClassNames the class names the catalogue knows as entities
     */
    EntityJson(Function<Class<?>, EntityModel<?>> models, Set<String> entityClassNames) {
        this.models = Objects.requireNonNull(models, "models");
        this.entities = Set.copyOf(entityClassNames);
    }

    /** Whether {@code type} is an entity of the catalogue. */
    boolean isEntity(Class<?> type) {
        return type != null && entities.contains(type.getName());
    }

    /**
     * The JSON Schema of {@code entity} (editor spec §6): an object, one property per settable attribute, each with
     * its column as {@code description}, {@code id of <Entity>, column <name>} for a reference. A generated id and the
     * version are {@code readOnly}: the database writes them. Any other attribute is {@code required} when its column
     * is not nullable and its field is not primitive, which always holds a value.
     *
     * @throws RuntimeException when its model cannot be read, as {@code EntityModels.of} throws it
     */
    Map<String, Object> schema(Class<?> entity) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (Map.Entry<String, Settable> entry : settable(model(entity)).entrySet()) {
            Settable property = entry.getValue();
            Attribute<?, ?> attribute = property.attribute();
            Map<String, Object> schema = Scalars.schema(property.type());
            if (attribute instanceof IdAttribute<?, ?> id && id.generated()
                    || attribute instanceof VersionAttribute<?, ?>) {
                schema.put("readOnly", true);
            } else if (!attribute.nullable() && !fieldType(attribute).isPrimitive()) {
                required.add(entry.getKey());
            }
            schema.put("description", (property.referenced() == null ? ""
                    : "id of " + property.referenced().entityClass().getSimpleName() + ", ")
                    + "column " + attribute.columnName());
            properties.put(entry.getKey(), schema);
        }
        return Scalars.object("type", "object", "properties", properties, "required", required);
    }

    /**
     * A new {@code entity} from {@code json}: its no-arg constructor, then each property present, converted as spec
     * §4 says; an absent one keeps the constructor's value.
     *
     * @param name the parameter it is for, which a refusal names, {@code <name>.<property>}
     * @throws ArgumentException for a value that is no object, an unknown property or a value that does not convert
     */
    Object fromJson(Class<?> entity, Object json, String name) throws ArgumentException {
        if (json == null) {
            return null;
        }
        if (!(json instanceof Map<?, ?> members)) {
            throw new ArgumentException(name, "not a JSON object");
        }
        EntityModel<?> model = model(entity);
        Map<String, Settable> settable = settable(model);
        Object instance = construct(model);
        for (Map.Entry<?, ?> member : members.entrySet()) {
            String path = name + "." + member.getKey();
            Settable property = settable.get(String.valueOf(member.getKey()));
            if (property == null) {
                throw new ArgumentException(path, "unknown property");
            }
            put(instance, property, Scalars.fromJson(property.type(), member.getValue(), path));
        }
        return instance;
    }

    /**
     * The names a CSV import may give {@code entity}'s attributes (CSV spec §4), in model order: those
     * {@link #fromJson} sets, each with a setter and a type {@link Scalars} converts, a reference by its id.
     *
     * @throws RuntimeException when its model cannot be read, as {@code EntityModels.of} throws it
     */
    List<String> columns(Class<?> entity) {
        return List.copyOf(settable(model(entity)).keySet());
    }

    /**
     * Builds {@code entity} instances from CSV records whose header is {@code columns}, the model read once: for each
     * record its no-arg constructor, then each column's text converted by the attribute's type
     * ({@link Scalars#fromText}) and set with its setter handle, a reference as a new instance of the referenced
     * entity holding that id. An attribute without a column keeps the constructor's value.
     *
     * @throws ArgumentException for a column no attribute of {@link #columns} has
     */
    TextRows textRows(Class<?> entity, List<String> columns) throws ArgumentException {
        EntityModel<?> model = model(entity);
        Map<String, Settable> settable = settable(model);
        List<Settable> properties = new ArrayList<>(columns.size());
        for (String column : columns) {
            Settable property = settable.get(column);
            if (property == null) {
                throw new ArgumentException(column, "not an attribute that can be set");
            }
            properties.add(property);
        }
        return values -> {
            Object instance = construct(model);
            for (int i = 0; i < properties.size(); i++) {
                Settable property = properties.get(i);
                put(instance, property, Scalars.fromText(property.type(), values.get(i), property.attribute().name()));
            }
            return instance;
        };
    }

    /** One entity from each CSV record of a {@link #textRows} call. */
    @FunctionalInterface
    interface TextRows {

        /**
         * @param values the record's fields, one per column, {@code null} for an empty unquoted one
         * @throws ArgumentException naming the column whose field does not convert: {@code price: not a number}
         */
        Object build(List<String> values) throws ArgumentException;
    }

    /** Sets {@code value} on {@code instance}; for a reference, a new instance of the referenced entity with that id. */
    private static void put(Object instance, Settable property, Object value) {
        Object set = value;
        if (property.referenced() != null && value != null) {
            set = construct(property.referenced());
            set(property.referenced().id().setter(), set, value);
        }
        set(property.attribute().setter(), instance, set);
    }

    /** {@code entity} as an object, one property per attribute in model order, a reference as its id. */
    Map<String, Object> toJson(Object entity) {
        EntityModel<?> model = model(entity.getClass());
        Map<String, Object> out = new LinkedHashMap<>();
        for (Attribute<?, ?> attribute : model.attributes()) {
            if (attribute == null || attribute instanceof JoinedAttribute<?, ?> || attribute.getter() == null) {
                continue;
            }
            Object value = get(attribute.getter(), entity);
            if (attribute instanceof ReferenceAttribute<?, ?> reference && value != null) {
                value = get(model(reference.javaType()).id().getter(), value);
            }
            out.put(attribute.name(), Scalars.toJson(value));
        }
        return out;
    }

    /**
     * The names {@link #toJson} gives {@code entity}'s instances, in model order: every attribute but a joined one or
     * one without a getter. The header of a CSV export (CSV spec §3).
     *
     * @throws RuntimeException when its model cannot be read, as {@code EntityModels.of} throws it
     */
    List<String> names(Class<?> entity) {
        List<String> names = new ArrayList<>();
        for (Attribute<?, ?> attribute : model(entity).attributes()) {
            if (attribute != null && !(attribute instanceof JoinedAttribute<?, ?>) && attribute.getter() != null) {
                names.add(attribute.name());
            }
        }
        return names;
    }

    /**
     * The type a table of rows shows for each name {@link #names} gives {@code entity}'s instances, in that order (SQL
     * spec §4.4): the JSON Schema type of the attribute's Java type, or its format for a date, a time or a uuid; for a
     * reference, its id's; empty when it has none.
     *
     * @throws RuntimeException when its model cannot be read, as {@code EntityModels.of} throws it
     */
    Map<String, String> types(Class<?> entity) {
        Map<String, String> types = new LinkedHashMap<>();
        for (Attribute<?, ?> attribute : model(entity).attributes()) {
            if (attribute == null || attribute instanceof JoinedAttribute<?, ?> || attribute.getter() == null) {
                continue;
            }
            Class<?> type = attribute.javaType();
            if (attribute instanceof ReferenceAttribute<?, ?> reference) {
                try {
                    type = model(reference.javaType()).id().javaType();
                } catch (RuntimeException | LinkageError unreadable) {
                    type = null;
                }
            }
            Map<String, Object> schema = type == null ? null : Scalars.schema(type);
            types.put(attribute.name(), schema == null ? "" : String.valueOf(schema.getOrDefault("format",
                    schema.get("type"))));
        }
        return types;
    }

    /** The id of {@code entity}, an instance of an entity: what a reference to it is written as. */
    Object idOf(Object entity) {
        return get(model(entity.getClass()).id().getter(), entity);
    }

    /**
     * One property a JSON object may set.
     *
     * @param attribute  its attribute
     * @param type       the Java type its value converts to: the field's own type, a primitive included, or for a
     *                   reference the referenced entity's id type
     * @param referenced for a reference, the model of the referenced entity; {@code null} otherwise
     */
    private record Settable(Attribute<?, ?> attribute, Class<?> type, EntityModel<?> referenced) {}

    private Map<String, Settable> settable(EntityModel<?> model) {
        Map<String, Settable> out = new LinkedHashMap<>();
        for (Attribute<?, ?> attribute : model.attributes()) {
            if (attribute == null || attribute instanceof JoinedAttribute<?, ?> || attribute.setter() == null) {
                continue;
            }
            try {
                EntityModel<?> referenced = attribute instanceof ReferenceAttribute<?, ?> reference
                        ? model(reference.javaType()) : null;
                Class<?> type = referenced == null ? fieldType(attribute) : fieldType(referenced.id());
                if (Scalars.schema(type) != null && (referenced == null || referenced.id().setter() != null)) {
                    out.put(attribute.name(), new Settable(attribute, type, referenced));
                }
            } catch (RuntimeException | LinkageError unreadable) {
                // a reference to an entity Mansart cannot model: left out, as a joined attribute is
            }
        }
        return out;
    }

    /** The type of the field behind {@code attribute}: its setter's, which keeps a primitive, else its Java type. */
    private static Class<?> fieldType(Attribute<?, ?> attribute) {
        MethodHandle setter = attribute.setter();
        return setter != null && setter.type().parameterCount() == 2
                ? setter.type().parameterType(1) : attribute.javaType();
    }

    private EntityModel<?> model(Class<?> type) {
        EntityModel<?> model = models.apply(type);
        if (model == null) {
            throw new IllegalStateException("no model of " + type.getName());
        }
        return model;
    }

    private static Object construct(EntityModel<?> model) {
        try {
            return model.constructor().invoke();
        } catch (Throwable failed) {
            throw unchecked(failed, "cannot build " + model.entityClass().getName());
        }
    }

    private static Object get(MethodHandle getter, Object entity) {
        try {
            return getter.invoke(entity);
        } catch (Throwable failed) {
            throw unchecked(failed, "cannot read " + entity.getClass().getName());
        }
    }

    private static void set(MethodHandle setter, Object entity, Object value) {
        try {
            setter.invoke(entity, value);
        } catch (Throwable failed) {
            throw unchecked(failed, "cannot set a value of " + entity.getClass().getName());
        }
    }

    private static RuntimeException unchecked(Throwable failed, String what) {
        if (failed instanceof RuntimeException runtime) {
            return runtime;
        }
        if (failed instanceof Error error) {
            throw error;
        }
        return new IllegalStateException(what, failed);
    }
}
