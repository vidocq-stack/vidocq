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
import io.vidocq.mansart.data.dialect.attribute.JoinedAttribute;
import io.vidocq.mansart.data.dialect.attribute.ReferenceAttribute;

import java.lang.invoke.MethodHandle;
import java.util.LinkedHashMap;
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
     * The JSON Schema of {@code entity}: an object, one property per settable attribute, none required.
     *
     * @throws RuntimeException when its model cannot be read, as {@code EntityModels.of} throws it
     */
    Map<String, Object> schema(Class<?> entity) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (Map.Entry<String, Settable> property : settable(model(entity)).entrySet()) {
            properties.put(property.getKey(), Scalars.schema(property.getValue().type()));
        }
        return Scalars.object("type", "object", "properties", properties);
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
            Object value = Scalars.fromJson(property.type(), member.getValue(), path);
            if (property.referenced() != null && value != null) {
                Object reference = construct(property.referenced());
                set(property.referenced().id().setter(), reference, value);
                value = reference;
            }
            set(property.attribute().setter(), instance, value);
        }
        return instance;
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
