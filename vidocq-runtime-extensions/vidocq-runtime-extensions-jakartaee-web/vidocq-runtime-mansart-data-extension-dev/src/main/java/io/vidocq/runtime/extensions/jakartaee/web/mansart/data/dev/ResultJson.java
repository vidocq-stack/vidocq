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

import io.vidocq.mansart.data.dialect.EntityModel;
import io.vidocq.runtime.spi.devconsole.PanelAction.ActionResult;
import io.vidocq.runtime.spi.devconsole.PanelAction.ActionResult.Column;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * What a repository method returned, as the JSON body of its result and the first words of its summary (spec §6): an
 * entity as an object, a {@code List}, {@code Collection}, {@code Stream} or array as an array of at most
 * {@value #MAX_ROWS} elements (a {@code Stream} read to the next one only, then closed), an {@code Optional} as its
 * value or {@code null}, {@code void} as nothing.
 */
final class ResultJson {

    /** The most elements an array holds. */
    static final int MAX_ROWS = 100;

    /** How deep nested collections are followed; past it an element is its text. */
    private static final int MAX_DEPTH = 8;

    /**
     * @param body the JSON text, {@code null} for {@code void}
     * @param what {@code 3 rows}, {@code first 100 rows}, {@code 1 row}, {@code no row}, the value such as
     *             {@code 42}, or {@code done}
     * @param rows whether it was a list of rows, whose summary says how long it took
     */
    record Result(String body, String what, boolean rows) {}

    private ResultJson() {}

    /**
     * @param value    what the method returned
     * @param isVoid   whether it returns {@code void}
     * @param entities how entities are written
     */
    static Result of(Object value, boolean isVoid, EntityJson entities) {
        if (isVoid) {
            return new Result(null, "done", false);
        }
        if (value == null) {
            return new Result("null", "no row", false);
        }
        if (value instanceof Optional<?> optional) {
            return optional.isPresent()
                    ? new Result(Json.write(node(optional.get(), entities, 0)), "1 row", false)
                    : new Result("null", "no row", false);
        }
        if (value instanceof Stream<?> stream) {
            try (stream) {
                List<Object> read = new ArrayList<>();
                Iterator<?> iterator = stream.iterator();
                while (read.size() <= MAX_ROWS && iterator.hasNext()) {
                    read.add(iterator.next());
                }
                return rows(read, entities);
            }
        }
        if (value instanceof Collection<?> collection) {
            List<Object> read = new ArrayList<>();
            for (Object row : collection) {
                if (read.size() > MAX_ROWS) {
                    break;
                }
                read.add(row);
            }
            return rows(read, entities);
        }
        if (value.getClass().isArray()) {
            List<Object> read = new ArrayList<>();
            int length = Array.getLength(value);
            for (int i = 0; i < length && i <= MAX_ROWS; i++) {
                read.add(Array.get(value, i));
            }
            return rows(read, entities);
        }
        Object node = node(value, entities, 0);
        return new Result(Json.write(node), entities.isEntity(value.getClass()) ? "1 row" : String.valueOf(node),
                false);
    }

    /** {@code read} holds up to {@value #MAX_ROWS} + 1 rows: the last one only says there were more. */
    private static Result rows(List<Object> read, EntityJson entities) {
        boolean more = read.size() > MAX_ROWS;
        List<Object> kept = more ? read.subList(0, MAX_ROWS) : read;
        List<Object> json = new ArrayList<>(kept.size());
        for (Object row : kept) {
            json.add(node(row, entities, 1));
        }
        return new Result(Json.write(json), more ? "first " + MAX_ROWS + " rows" : count(kept.size()), true);
    }

    /** {@code no row}, {@code 1 row} or {@code N rows}. */
    static String count(long n) {
        return n == 0 ? "no row" : n == 1 ? "1 row" : n + " rows";
    }

    /**
     * The rows of a projection (JDQL console spec §3): an array of at most {@value #MAX_ROWS} objects, one member per
     * column in the columns' order, each value written as an element of a list is; a column named twice keeps its
     * last value.
     */
    static Result table(List<String> columns, List<Object[]> rows, EntityJson entities) {
        boolean more = rows.size() > MAX_ROWS;
        List<Object> json = new ArrayList<>();
        for (Object[] row : more ? rows.subList(0, MAX_ROWS) : rows) {
            Map<String, Object> object = new LinkedHashMap<>();
            for (int i = 0; i < columns.size(); i++) {
                object.put(columns.get(i), node(i < row.length ? row[i] : null, entities, 1));
            }
            json.add(object);
        }
        return new Result(Json.write(json), more ? "first " + MAX_ROWS + " rows" : count(json.size()), true);
    }

    /**
     * Entities a JDQL query found as a table of rows (SQL spec §4.4): one column per name its objects have, in model
     * order, typed from the attributes ({@link EntityJson#types}); at most {@value #MAX_ROWS} rows, the next one
     * setting {@code more}.
     *
     * @param model the model of the entity the query names
     */
    static Result rows(List<?> found, EntityModel<?> model, EntityJson entities) {
        boolean more = found.size() > MAX_ROWS;
        Map<String, String> types = entities.types(model.entityClass());
        List<List<Object>> rows = new ArrayList<>();
        for (Object entity : more ? found.subList(0, MAX_ROWS) : found) {
            Map<String, Object> json = entities.toJson(entity);
            rows.add(types.keySet().stream().map(json::get).toList());
        }
        List<Column> columns = types.entrySet().stream().map(type -> new Column(type.getKey(), type.getValue()))
                .toList();
        return new Result(ActionResult.rows(null, columns, rows, more).body(),
                more ? "first " + MAX_ROWS + " rows" : count(rows.size()), true);
    }

    /**
     * The rows of a JDQL projection as a table of rows (SQL spec §4.4): its columns in order, each typed as the
     * attribute of that name of {@code model}, or {@code object} when it holds entities; an entity, a list, written as
     * its JSON text; at most {@value #MAX_ROWS} rows, the next one setting {@code more}.
     */
    static Result rows(List<String> columns, List<Object[]> rows, EntityModel<?> model, EntityJson entities) {
        boolean more = rows.size() > MAX_ROWS;
        List<Object[]> kept = more ? rows.subList(0, MAX_ROWS) : rows;
        Map<String, String> types = entities.types(model.entityClass());
        List<Column> shown = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            int at = i;
            boolean holdsEntities = kept.stream().map(row -> at < row.length ? row[at] : null)
                    .anyMatch(value -> value != null && entities.isEntity(value.getClass()));
            shown.add(new Column(columns.get(i), holdsEntities ? "object" : types.getOrDefault(columns.get(i), "")));
        }
        List<List<Object>> out = new ArrayList<>();
        for (Object[] row : kept) {
            List<Object> values = new ArrayList<>(columns.size());
            for (int i = 0; i < columns.size(); i++) {
                Object node = node(i < row.length ? row[i] : null, entities, 1);
                values.add(node instanceof Map<?, ?> || node instanceof List<?> ? Json.write(node) : node);
            }
            out.add(values);
        }
        return new Result(ActionResult.rows(null, shown, out, more).body(),
                more ? "first " + MAX_ROWS + " rows" : count(out.size()), true);
    }

    /** {@code value} as JSON, as an element of a list is written: an entity as an object, a scalar as itself. */
    static Object node(Object value, EntityJson entities) {
        return node(value, entities, 1);
    }

    private static Object node(Object value, EntityJson entities, int depth) {
        if (value == null) {
            return null;
        }
        if (entities.isEntity(value.getClass())) {
            return entities.toJson(value);
        }
        if (depth < MAX_DEPTH) {
            if (value instanceof Optional<?> optional) {
                return optional.isPresent() ? node(optional.get(), entities, depth + 1) : null;
            }
            if (value instanceof Collection<?> collection) {
                List<Object> out = new ArrayList<>();
                for (Object element : collection) {
                    if (out.size() == MAX_ROWS) {
                        break;
                    }
                    out.add(node(element, entities, depth + 1));
                }
                return out;
            }
            if (value.getClass().isArray()) {
                List<Object> out = new ArrayList<>();
                int length = Math.min(Array.getLength(value), MAX_ROWS);
                for (int i = 0; i < length; i++) {
                    out.add(node(Array.get(value, i), entities, depth + 1));
                }
                return out;
            }
        }
        return Scalars.toJson(value);
    }
}
