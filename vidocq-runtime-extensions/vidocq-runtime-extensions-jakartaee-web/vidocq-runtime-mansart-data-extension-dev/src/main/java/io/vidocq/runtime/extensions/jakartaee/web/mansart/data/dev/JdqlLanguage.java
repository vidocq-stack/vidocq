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
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.live.MansartDataCatalogue;
import io.vidocq.runtime.spi.devconsole.PanelLanguage;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The {@value #ID} language of the Mansart Data panel (query mode spec §2.4), which the page's query editor fetches
 * once per boot: the dialect of JDQL as Mansart's parser reads it, and one target per entity of the catalogue whose
 * model could be read, under the name {@code FROM} takes, its simple name, with every attribute of its model but the
 * joined ones. An attribute's type is its Java type's JSON Schema type ({@link Scalars#schema}), a reference's the
 * type of the id of the entity it refers to, which it names as its {@code target}. Built from memory: no bean, no
 * query.
 */
final class JdqlLanguage {

    /** The language's id, which the query property of the tab's actions names in {@code x-language}. */
    static final String ID = "jdql";
    /** The media type of a property that is a query: the page shows it in its query editor. */
    static final String MEDIA_TYPE = "text/x-query";
    /** The words Mansart's JDQL reads as keywords, and its literals. */
    static final List<String> KEYWORDS = List.of("SELECT", "FROM", "WHERE", "ORDER", "BY", "AND", "OR", "NOT", "IS",
            "NULL", "BETWEEN", "LIKE", "IN", "ASC", "DESC", "UPDATE", "SET", "DELETE", "COUNT", "THIS", "SUM", "AVG",
            "MIN", "MAX", "TRUE", "FALSE");
    /** Its scalar functions and aggregates, each followed by {@code (}. */
    static final List<String> FUNCTIONS = List.of("UPPER", "LOWER", "LENGTH", "ABS", "CONCAT", "COUNT", "SUM", "AVG",
            "MIN", "MAX");
    /** The words that start a clause. */
    static final List<String> CLAUSES = List.of("SELECT", "FROM", "WHERE", "ORDER BY", "SET", "UPDATE", "DELETE FROM");
    /** The words after which the entity is named. */
    static final List<String> TARGET_AFTER = List.of("FROM", "UPDATE");

    private static final System.Logger LOG = System.getLogger(JdqlLanguage.class.getName());

    private JdqlLanguage() {}

    /**
     * The language's JSON, {@code {"mode": "query", "dialect": {…}, "targets": {…}}}, its targets in name order. An
     * entity whose model could not be read, whose class does not load, or whose simple name another entity shares
     * (JDQL names an entity by its simple name, and the tab refuses an ambiguous one) is left out, as is an
     * attribute Mansart cannot describe.
     *
     * @param entities the catalogue's entities
     * @param classes  the class of an entity by its full name, as the application loads it
     * @param models   the model of an entity class, {@code EntityModels.of} outside tests
     */
    static String json(List<MansartDataCatalogue.Entity> entities, Function<String, Class<?>> classes,
                       Function<Class<?>, EntityModel<?>> models) {
        return json(entities, classes, models, PanelLanguage.MAX_JSON);
    }

    /**
     * {@link #json(List, Function, Function)} within {@code limit} characters: past it, the language is written again
     * without the {@code detail}s, then without the {@code enum}s too, which the editor can do without (every target
     * and attribute stays, typed), saying so; past it still, it is refused.
     *
     * @throws IllegalStateException when even the leanest language is past {@code limit}, naming its size
     */
    static String json(List<MansartDataCatalogue.Entity> entities, Function<String, Class<?>> classes,
                       Function<Class<?>, EntityModel<?>> models, int limit) {
        Map<String, Object> targets = targets(entities, classes, models);
        String json = write(targets);
        if (json.length() <= limit) {
            return json;
        }
        int full = json.length();
        for (String dropped : List.of("detail", "enum")) {
            drop(targets, dropped);
            json = write(targets);
            if (json.length() <= limit) {
                LOG.log(System.Logger.Level.INFO, "Mansart Data: the JDQL language of " + targets.size()
                        + " entities is " + full + " characters, past the " + limit + " a panel language may hold:"
                        + " written without " + (dropped.equals("detail") ? "details" : "details or enums"));
                return json;
            }
        }
        throw new IllegalStateException("the JDQL language of " + targets.size() + " entities is " + json.length()
                + " characters even without details or enums, past the " + limit + " a panel language may hold");
    }

    private static String write(Map<String, Object> targets) {
        return Json.write(Scalars.object("mode", "query",
                "dialect", Scalars.object("keywords", KEYWORDS, "functions", FUNCTIONS, "clauses", CLAUSES,
                        "targetAfter", TARGET_AFTER, "self", "this", "quote", "'"),
                "targets", targets));
    }

    /** Removes {@code key} from every target and every attribute of {@code targets}. */
    private static void drop(Map<String, Object> targets, String key) {
        for (Object target : targets.values()) {
            Map<?, ?> entry = (Map<?, ?>) target;
            entry.remove(key);
            for (Object attribute : ((Map<?, ?>) entry.get("attributes")).values()) {
                ((Map<?, ?>) attribute).remove(key);
            }
        }
    }

    private static Map<String, Object> targets(List<MansartDataCatalogue.Entity> entities,
                                               Function<String, Class<?>> classes,
                                               Function<Class<?>, EntityModel<?>> models) {
        Map<String, Integer> uses = new HashMap<>();
        for (MansartDataCatalogue.Entity entity : entities) {
            uses.merge(simpleName(entity.className()), 1, Integer::sum);
        }
        Map<String, Object> targets = new LinkedHashMap<>();
        for (MansartDataCatalogue.Entity entity : entities.stream()
                .sorted(Comparator.comparing(MansartDataCatalogue.Entity::name)).toList()) {
            String name = simpleName(entity.className());
            EntityModel<?> model = entity.failure() != null || uses.get(name) > 1 ? null
                    : model(entity.className(), classes, models);
            if (model != null) {
                targets.put(name, Scalars.object("detail", "table " + entity.table(),
                        "attributes", attributes(model, models)));
            }
        }
        return targets;
    }

    /** The model of the entity {@code className}, or {@code null} when its class or its model cannot be had. */
    private static EntityModel<?> model(String className, Function<String, Class<?>> classes,
                                        Function<Class<?>, EntityModel<?>> models) {
        try {
            return models.apply(classes.apply(className));
        } catch (RuntimeException | LinkageError unreadable) {
            return null;
        }
    }

    /** One attribute per attribute of {@code model} but the joined ones, in model order. */
    private static Map<String, Object> attributes(EntityModel<?> model, Function<Class<?>, EntityModel<?>> models) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Attribute<?, ?> attribute : model.attributes()) {
            if (attribute == null || attribute instanceof JoinedAttribute<?, ?>) {
                continue;
            }
            try {
                out.put(attribute.name(), attribute(attribute, models));
            } catch (RuntimeException | LinkageError unreadable) {
                // an attribute Mansart cannot describe is left out, as a joined one is
            }
        }
        return out;
    }

    /**
     * An attribute: its {@code type}, {@code format} and {@code enum} when its Java type has a JSON Schema, then its
     * {@code detail}, {@code <Java type> · <key or column>}, and for a reference its {@code target}.
     */
    private static Map<String, Object> attribute(Attribute<?, ?> attribute,
                                                 Function<Class<?>, EntityModel<?>> models) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (attribute instanceof ReferenceAttribute<?, ?> reference) {
            String target = simpleName(reference.javaType().getName());
            typeOf(idType(reference.javaType(), models), out);
            out.put("detail", "→ " + target + " · column " + attribute.columnName());
            out.put("target", target);
            return out;
        }
        Class<?> type = attribute.javaType();
        typeOf(type, out);
        String where = switch (attribute) {
            case IdAttribute<?, ?> id -> id.generated() ? "id, generated" : "id";
            case VersionAttribute<?, ?> _ -> "version";
            default -> "column " + attribute.columnName();
        };
        out.put("detail", (type == null ? "?" : type.getSimpleName().isEmpty() ? type.getName()
                : type.getSimpleName()) + " · " + where);
        return out;
    }

    /** Puts the {@code type}, {@code format} and {@code enum} of {@code type}'s JSON Schema into {@code out}. */
    private static void typeOf(Class<?> type, Map<String, Object> out) {
        Map<String, Object> schema = type == null ? null : Scalars.schema(type);
        if (schema == null) {
            return;
        }
        for (String keyword : List.of("type", "format", "enum")) {
            if (schema.containsKey(keyword)) {
                out.put(keyword, schema.get(keyword));
            }
        }
    }

    /** The type of the id of {@code entity}, or {@code null} when its model cannot be read. */
    private static Class<?> idType(Class<?> entity, Function<Class<?>, EntityModel<?>> models) {
        try {
            EntityModel<?> model = models.apply(entity);
            return model == null || model.id() == null ? null : model.id().javaType();
        } catch (RuntimeException | LinkageError unreadable) {
            return null;
        }
    }

    /** {@code io.x.Outer$Gizmo} → {@code Gizmo}: the name a statement uses. */
    private static String simpleName(String className) {
        String name = className.substring(className.lastIndexOf('.') + 1);
        return name.substring(name.lastIndexOf('$') + 1);
    }
}
