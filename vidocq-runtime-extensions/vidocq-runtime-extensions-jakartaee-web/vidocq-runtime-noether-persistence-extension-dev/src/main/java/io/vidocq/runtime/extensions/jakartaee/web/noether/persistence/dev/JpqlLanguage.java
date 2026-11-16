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
import io.vidocq.noether.jpa.core.spi.UnitDescription.Entity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@value #ID} language of the query editor (the dev console's {@code PanelLanguage}): SQL's aliases, since a
 * JPQL statement names its entities with identification variables, JPQL's words, and a target per entity of the
 * application's units, each attribute typed, a relation pointing at its target.
 */
final class JpqlLanguage {

    /** The language's id, which a query property names in {@code x-language}. */
    static final String ID = "jpql";

    /** The media type of a query property. */
    static final String MEDIA_TYPE = "text/x-query";

    static final List<String> KEYWORDS = List.of("SELECT", "FROM", "WHERE", "GROUP", "BY", "HAVING", "ORDER", "ASC",
            "DESC", "NULLS", "FIRST", "LAST", "AS", "DISTINCT", "JOIN", "LEFT", "INNER", "OUTER", "FETCH", "ON",
            "UPDATE", "SET", "DELETE", "NEW", "AND", "OR", "NOT", "IS", "NULL", "EMPTY", "MEMBER", "OF", "IN", "LIKE",
            "ESCAPE", "BETWEEN", "EXISTS", "ALL", "ANY", "SOME", "CASE", "WHEN", "THEN", "ELSE", "END", "TRUE",
            "FALSE", "UNION", "INTERSECT", "EXCEPT", "TYPE", "TREAT", "KEY", "VALUE", "ENTRY");
    static final List<String> FUNCTIONS = List.of("UPPER", "LOWER", "LENGTH", "LOCATE", "SUBSTRING", "TRIM", "CONCAT",
            "LEFT", "RIGHT", "REPLACE", "ABS", "CEILING", "FLOOR", "EXP", "LN", "POWER", "ROUND", "SIGN", "SQRT",
            "MOD", "SIZE", "INDEX", "COUNT", "SUM", "AVG", "MIN", "MAX", "COALESCE", "NULLIF", "EXTRACT", "CAST",
            "ID", "VERSION", "CURRENT_DATE", "CURRENT_TIME", "CURRENT_TIMESTAMP", "LOCAL");
    static final List<String> CLAUSES = List.of("SELECT", "FROM", "WHERE", "GROUP BY", "HAVING", "ORDER BY", "SET",
            "UPDATE", "DELETE FROM");

    private JpqlLanguage() {
    }

    /**
     * The language's JSON, {@code {"mode": "query", "dialect": {…}, "targets": {…}}}, its targets the entities of
     * {@code units}: an entity two units name is the first one's.
     */
    static String json(List<UnitDescription> units) {
        Map<String, Entity> entities = new LinkedHashMap<>();
        units.forEach(unit -> unit.entities().forEach(entity -> entities.putIfAbsent(entity.name(), entity)));
        Map<String, Object> targets = new LinkedHashMap<>();
        entities.values().forEach(entity -> targets.put(entity.name(), target(entity, entities)));
        Map<String, Object> dialect = Json.object("keywords", KEYWORDS, "functions", FUNCTIONS, "clauses", CLAUSES,
                "targetAfter", List.of("FROM", "JOIN", "UPDATE"), "self", null, "quote", "'", "aliases", true,
                "identifierQuote", null);
        return Json.write(Json.object("mode", "query", "dialect", dialect, "targets", targets));
    }

    private static Map<String, Object> target(Entity entity, Map<String, Entity> entities) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        for (Attribute attribute : entity.attributes()) {
            attributes.put(attribute.name(), attribute(attribute, entities));
        }
        String table = "table " + (entity.table() == null ? "none" : entity.table());
        return Json.object("detail", entity.isAbstract() ? "abstract entity, " + table : table,
                "attributes", attributes);
    }

    /**
     * An attribute: its {@code type} and {@code format} — for a single-valued relation its target's id's, none for a
     * collection or an embedded value —, its {@code detail}
     * as the Model tab says it, and for a relation to an entity of the language its {@code target}.
     */
    private static Map<String, Object> attribute(Attribute attribute, Map<String, Entity> entities) {
        Map<String, Object> out = new LinkedHashMap<>();
        Entity target = attribute.target() == null ? null : entities.get(attribute.target());
        String javaType = switch (attribute.kind()) {
            case MANY_TO_ONE, ONE_TO_ONE -> target == null ? null
                    : Projection.simpleId(target).map(Attribute::javaType).orElse(null);
            case ONE_TO_MANY, MANY_TO_MANY, ELEMENT_COLLECTION, EMBEDDED, EMBEDDED_ID -> null;
            default -> attribute.javaType();
        };
        if (javaType != null) {
            out.put("type", JavaTypes.type(javaType));
            String format = JavaTypes.format(javaType);
            if (format != null) {
                out.put("format", format);
            }
        }
        out.put("detail", ModelGraph.fact(attribute).value());
        if (target != null) {
            out.put("target", target.name());
        }
        return out;
    }
}
