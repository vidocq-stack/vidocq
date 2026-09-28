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

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * What the page asks for one method and how it becomes the call's arguments (spec §4): a JSON Schema with one
 * property per parameter, in order, all required; or why the method cannot run — its package is not open to Vidocq
 * (spec §3), a parameter type spec §4 does not list, or an entity whose model cannot be read.
 */
final class Signature {

    /**
     * One parameter.
     *
     * @param name   its name, which is its property's
     * @param type   its class, type variables bound
     * @param entity whether it is an entity of the catalogue
     */
    record Parameter(String name, Class<?> type, boolean entity) {}

    private final List<Parameter> parameters;
    private final String reason;
    private final String schema;
    private final EntityJson entities;

    private Signature(List<Parameter> parameters, String reason, String schema, EntityJson entities) {
        this.parameters = parameters;
        this.reason = reason;
        this.schema = schema;
        this.entities = entities;
    }

    /**
     * @param candidate  the method
     * @param entities   the catalogue's entities
     * @param accessible whether reflection may call the method; it makes it accessible when it can
     */
    static Signature of(RepositoryMethods.Candidate candidate, EntityJson entities, Predicate<Method> accessible) {
        Method method = candidate.method();
        String reason = accessible.test(method) ? null
                : "package " + method.getDeclaringClass().getPackageName() + " not open to Vidocq";
        List<Parameter> parameters = new ArrayList<>();
        Map<String, Object> properties = new LinkedHashMap<>();
        for (int i = 0; i < candidate.types().size(); i++) {
            String name = candidate.names().get(i);
            Class<?> type = candidate.types().get(i);
            boolean entity = entities.isEntity(type);
            parameters.add(new Parameter(name, type, entity));
            if (reason != null) {
                continue;
            }
            Map<String, Object> property;
            try {
                property = entity ? entities.schema(type) : Scalars.schema(type);
            } catch (RuntimeException | LinkageError failed) {
                reason = "parameter " + name + ": " + type.getSimpleName() + " has no model ("
                        + failed.getClass().getName() + ")";
                continue;
            }
            if (property == null) {
                reason = "parameter " + name + ": " + type.getSimpleName() + " is not supported";
            } else {
                properties.put(name, property);
            }
        }
        String schema = reason != null ? null : Json.write(Scalars.object("type", "object", "properties", properties,
                "required", parameters.stream().map(Parameter::name).toList()));
        return new Signature(List.copyOf(parameters), reason, schema, entities);
    }

    List<Parameter> parameters() {
        return parameters;
    }

    /** Why the method cannot run, {@code null} when it can. */
    String reason() {
        return reason;
    }

    /** The schema of its {@code arguments}, {@code null} when it cannot run. */
    String schema() {
        return schema;
    }

    /**
     * The call's arguments from the JSON object the page sent, in the parameters' order.
     *
     * @throws ArgumentException for a value that is no object, a property no parameter has, a missing one, or a value
     *                           that does not convert
     */
    Object[] arguments(Object json) throws ArgumentException {
        if (!(json instanceof Map<?, ?> members)) {
            throw new ArgumentException("arguments", "not a JSON object");
        }
        for (Object key : members.keySet()) {
            if (parameters.stream().noneMatch(parameter -> parameter.name().equals(key))) {
                throw new ArgumentException(String.valueOf(key), "unknown argument");
            }
        }
        Object[] values = new Object[parameters.size()];
        for (int i = 0; i < values.length; i++) {
            Parameter parameter = parameters.get(i);
            if (!members.containsKey(parameter.name())) {
                throw new ArgumentException(parameter.name(), "missing");
            }
            Object value = members.get(parameter.name());
            values[i] = parameter.entity() ? entities.fromJson(parameter.type(), value, parameter.name())
                    : Scalars.fromJson(parameter.type(), value, parameter.name());
        }
        return values;
    }
}
