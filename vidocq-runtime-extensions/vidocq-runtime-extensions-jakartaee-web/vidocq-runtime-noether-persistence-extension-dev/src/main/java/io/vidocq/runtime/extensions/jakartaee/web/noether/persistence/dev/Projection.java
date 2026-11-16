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
package io.vidocq.runtime.extensions.jakartaee.web.noether.persistence.dev;

import io.vidocq.noether.jpa.core.spi.UnitDescription;
import io.vidocq.noether.jpa.core.spi.UnitDescription.Attribute;
import io.vidocq.noether.jpa.core.spi.UnitDescription.Attribute.Kind;
import io.vidocq.noether.jpa.core.spi.UnitDescription.Embeddable;
import io.vidocq.noether.jpa.core.spi.UnitDescription.Entity;
import io.vidocq.runtime.spi.devconsole.PanelAction.ActionResult.Column;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The values an entity holds, as the columns of a JPQL query: no reflection reads the entity, the database answers
 * {@code SELECT e.id, e.title, j1.id FROM Note e LEFT JOIN e.author j1}. A basic value is a column, an embedded value
 * a column per value of its embeddable, a single-valued relation the id of its target, joined so that a relation
 * without a target still gives its row; a collection is left out.
 *
 * @param entity  the entity's name
 * @param paths   the path of each column, in order
 * @param joins   the joins the paths need, {@code LEFT JOIN e.author j1}
 * @param columns the columns, named by the attribute, {@code title}, {@code author.id} or {@code address.city}
 * @param idPath  the path of the entity's id, {@code e.id}; {@code null} when its id is composite
 * @param idPaths the paths of the values of its id, which sort a page; empty when an embedded id has no known values
 */
record Projection(String entity, List<String> paths, List<String> joins, List<Column> columns, String idPath,
                  List<String> idPaths) {

    /** The entity's identification variable. */
    static final String ALIAS = "e";

    /** How deep an embeddable within an embeddable is followed. */
    private static final int MAX_NESTING = 3;
    private static final String STRING = "string";

    Projection {
        paths = List.copyOf(paths);
        joins = List.copyOf(joins);
        columns = List.copyOf(columns);
        idPaths = List.copyOf(idPaths);
    }

    /** The projection of {@code entity}, an entity of {@code unit}. */
    static Projection of(Entity entity, UnitDescription unit) {
        Map<String, Entity> entities = unit.entities().stream()
                .collect(Collectors.toMap(Entity::name, Function.identity(), (a, b) -> a));
        Map<String, Embeddable> embeddables = unit.embeddables().stream()
                .collect(Collectors.toMap(Embeddable::className, Function.identity(), (a, b) -> a));
        Builder builder = new Builder(embeddables);
        String idPath = simpleId(entity).map(id -> ALIAS + "." + id.name()).orElse(null);
        List<String> idPaths = new ArrayList<>();
        for (Attribute attribute : entity.attributes()) {
            String path = ALIAS + "." + attribute.name();
            switch (attribute.kind()) {
                case ID -> {
                    builder.add(path, attribute.name(), JavaTypes.column(attribute.javaType()));
                    idPaths.add(path);
                }
                case VERSION, BASIC -> builder.add(path, attribute.name(), JavaTypes.column(attribute.javaType()));
                case EMBEDDED -> builder.embedded(path, attribute.name(), attribute, 0);
                case EMBEDDED_ID -> {
                    int first = builder.paths.size();
                    builder.embedded(path, attribute.name(), attribute, 0);
                    if (embeddables.containsKey(attribute.target())) {
                        idPaths.addAll(builder.paths.subList(first, builder.paths.size()));
                    }
                }
                case MANY_TO_ONE, ONE_TO_ONE -> {
                    Entity target = entities.get(attribute.target());
                    Optional<Attribute> id = target == null ? Optional.empty() : simpleId(target);
                    id.ifPresent(targetId -> builder.joined(path, attribute.name(), targetId));
                }
                default -> {
                    // a collection is no column
                }
            }
        }
        return new Projection(entity.name(), builder.paths, builder.joins, builder.columns, idPath, idPaths);
    }

    /** The id of {@code entity} when it is one basic attribute; empty for an embedded or a composite id. */
    static Optional<Attribute> simpleId(Entity entity) {
        List<Attribute> ids = entity.attributes().stream()
                .filter(a -> a.kind() == Kind.ID || a.kind() == Kind.EMBEDDED_ID).toList();
        return ids.size() == 1 && ids.getFirst().kind() == Kind.ID ? Optional.of(ids.getFirst()) : Optional.empty();
    }

    /** {@code SELECT <paths> FROM <entity> e <joins>}. */
    String select() {
        StringBuilder out = new StringBuilder("SELECT ").append(String.join(", ", paths)).append(" FROM ")
                .append(entity).append(' ').append(ALIAS);
        joins.forEach(join -> out.append(' ').append(join));
        return out.toString();
    }

    /** The index of the column of the entity's id, {@code -1} when it has no {@link #idPath}. */
    int idColumn() {
        return idPath == null ? -1 : paths.indexOf(idPath);
    }

    /** Collects the paths, joins and columns of a projection. */
    private static final class Builder {

        private final Map<String, Embeddable> embeddables;
        private final List<String> paths = new ArrayList<>();
        private final List<String> joins = new ArrayList<>();
        private final List<Column> columns = new ArrayList<>();

        Builder(Map<String, Embeddable> embeddables) {
            this.embeddables = embeddables;
        }

        void add(String path, String name, String type) {
            paths.add(path);
            columns.add(new Column(name, type));
        }

        /** The values of an embedded {@code attribute}, or the embedded value itself when its embeddable is unknown. */
        void embedded(String path, String name, Attribute attribute, int depth) {
            Embeddable embeddable = embeddables.get(attribute.target());
            if (embeddable == null || depth >= MAX_NESTING) {
                add(path, name, STRING);
                return;
            }
            for (Attribute value : embeddable.attributes()) {
                String inner = path + "." + value.name();
                String innerName = name + "." + value.name();
                switch (value.kind()) {
                    case BASIC, ID, VERSION -> add(inner, innerName, JavaTypes.column(value.javaType()));
                    case EMBEDDED, EMBEDDED_ID -> embedded(inner, innerName, value, depth + 1);
                    default -> {
                        // a relation or a collection of an embeddable is no column
                    }
                }
            }
        }

        /** The id of the target of a single-valued relation, through a left join. */
        void joined(String path, String name, Attribute targetId) {
            String variable = "j" + (joins.size() + 1);
            joins.add("LEFT JOIN " + path + " " + variable);
            add(variable + "." + targetId.name(), name + "." + targetId.name(), JavaTypes.column(targetId.javaType()));
        }
    }
}
