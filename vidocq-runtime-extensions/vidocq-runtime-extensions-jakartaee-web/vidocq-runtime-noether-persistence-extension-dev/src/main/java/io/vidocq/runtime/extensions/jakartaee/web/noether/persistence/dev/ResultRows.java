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
import io.vidocq.noether.jpa.core.spi.UnitDescription.Entity;
import io.vidocq.runtime.spi.devconsole.PanelAction.ActionResult;
import io.vidocq.runtime.spi.devconsole.PanelAction.ActionResult.Column;
import io.vidocq.runtime.spi.devconsole.PanelAction.ActionResult.MenuItem;
import jakarta.persistence.EntityManager;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The results of a query as a table of rows: a column per item of its select list, named by the item or its alias;
 * an entity, when the query returns entities, as the values of its {@link Projection}, read by a second query on their
 * ids; any other value as text.
 */
final class ResultRows {

    /** The most rows a query shows. */
    static final int MAX_ROWS = 100;

    /** How many ids one query of a projection takes. */
    private static final int CHUNK = 500;
    private static final String SELECT = "SELECT";
    private static final Pattern DISTINCT = Pattern.compile("(?i)DISTINCT\\s+");
    private static final Pattern ALIASED = Pattern.compile("(?is).*\\S\\s+AS\\s+([\\p{L}_][\\p{L}\\p{N}_]*)");
    private static final Pattern NAMED = Pattern.compile("\\S+\\s+([\\p{L}_][\\p{L}\\p{N}_]*)");

    /** The id of an entity, for a table that holds none. */
    private static final Function<Object, Object> NO_ENTITY = item -> null;

    /**
     * A table.
     *
     * @param columns its columns
     * @param rows    its rows, each a value per column, as {@link ActionResult#rows} takes them
     */
    record Table(List<Column> columns, List<List<Object>> rows) {}

    private ResultRows() {
    }

    /**
     * The table of {@code found}, the results of {@code query}.
     *
     * @param em   the entity manager that ran it, which reads the projection of its entities; {@code null} for none
     * @param unit the unit, whose entities the results may be
     * @param ids  the id of an entity, {@code PersistenceUnitUtil.getIdentifier}
     */
    static Table table(String query, List<?> found, EntityManager em, UnitDescription unit,
                       Function<Object, Object> ids) {
        Map<String, Entity> byClass = unit.entities().stream()
                .collect(Collectors.toMap(Entity::className, Function.identity(), (a, b) -> a));
        Entity entity = common(found, byClass, unit);
        if (entity != null && em != null) {
            Projection projection = Projection.of(entity, unit);
            if (projection.idPath() != null) {
                return expanded(found, projection, em, ids);
            }
        }
        List<Object[]> values = found.stream().map(item -> item instanceof Object[] array ? array
                : new Object[] {item}).toList();
        List<String> names = selectList(query);
        int width = values.isEmpty() ? names.size() : values.stream().mapToInt(v -> v.length).max().orElse(0);
        List<List<Object>> rows = values.stream().map(v -> cells(v, width, byClass, ids)).toList();
        if (names.size() != width) {
            names = new ArrayList<>();
            for (int i = 1; i <= width; i++) {
                names.add(Integer.toString(i));
            }
        }
        return new Table(columns(names, rows), rows);
    }

    /** The table of {@code found}, the rows of {@code projection}'s query. */
    static Table projected(Projection projection, List<?> found) {
        int width = projection.columns().size();
        List<List<Object>> rows = found.stream().map(row -> cells(row instanceof Object[] array ? array
                : new Object[] {row}, width, Map.of(), NO_ENTITY)).toList();
        return new Table(projection.columns(), rows);
    }

    /** The answer of a query: at most {@value #MAX_ROWS} rows of {@code table}, run in {@code millis}. */
    static ActionResult shown(Table table, long millis) {
        return shown(table, millis, List.of());
    }

    /** {@link #shown(Table, long)}, its rows offering {@code menu}. */
    static ActionResult shown(Table table, long millis, List<MenuItem> menu) {
        boolean more = table.rows().size() > MAX_ROWS;
        List<List<Object>> kept = more ? table.rows().subList(0, MAX_ROWS) : table.rows();
        int held = ActionResult.rowsHeld(table.columns(), kept, menu);
        String rows = count(held, "row", "rows");
        String summary = (more || held < kept.size() ? "first " + rows : rows) + " in " + millis + " ms";
        return ActionResult.rowsWithMenu(summary, table.columns(), kept, more, menu);
    }

    /** {@code n} and the noun that fits it: {@code 1 row}, {@code 3 rows}. */
    static String count(long n, String one, String many) {
        return n + " " + (n == 1 ? one : many);
    }

    /**
     * The names of the items of {@code query}'s select list, their aliases when they have one; empty when it has no
     * select list or it cannot be read.
     */
    static List<String> selectList(String query) {
        String text = query.strip();
        if (!text.regionMatches(true, 0, SELECT, 0, SELECT.length()) || !boundary(text, SELECT.length())) {
            return List.of();
        }
        List<String> items = new ArrayList<>();
        int depth = 0;
        boolean quoted = false;
        int start = SELECT.length();
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\'') {
                quoted = !quoted;
            } else if (!quoted) {
                depth += c == '(' ? 1 : 0;
                depth -= c == ')' ? 1 : 0;
                boolean from = c != ',' && isFrom(text, i);
                if (depth == 0 && (c == ',' || from)) {
                    items.add(name(text.substring(start, i), items.isEmpty()));
                    start = i + 1;
                }
                if (depth == 0 && from) {
                    return items;
                }
            }
        }
        return List.of();
    }

    private static boolean isFrom(String text, int at) {
        return text.regionMatches(true, at, "FROM", 0, 4) && boundary(text, at + 4)
                && (at == 0 || !Character.isJavaIdentifierPart(text.charAt(at - 1)) && text.charAt(at - 1) != '.');
    }

    private static boolean boundary(String text, int at) {
        return at >= text.length() || !Character.isJavaIdentifierPart(text.charAt(at));
    }

    private static String name(String item, boolean first) {
        String name = item.strip();
        if (first) {
            Matcher distinct = DISTINCT.matcher(name);
            if (distinct.lookingAt()) {
                name = name.substring(distinct.end());
            }
        }
        Matcher aliased = ALIASED.matcher(name);
        Matcher named = NAMED.matcher(name);
        if (aliased.matches()) {
            name = aliased.group(1);
        } else if (named.matches()) {
            name = named.group(1);
        }
        return name.length() <= Column.MAX_NAME ? name : name.substring(0, Column.MAX_NAME - 1) + "…";
    }

    /**
     * The entity every one of {@code found} is, or the nearest entity they all extend; {@code null} when one is no
     * entity of the unit, an array or {@code null}.
     */
    private static Entity common(List<?> found, Map<String, Entity> byClass, UnitDescription unit) {
        Set<String> shared = null;
        for (Object item : found) {
            Entity entity = item == null ? null : byClass.get(item.getClass().getName());
            if (entity == null) {
                return null;
            }
            Set<String> lineage = lineage(entity, unit);
            if (shared == null) {
                shared = lineage;
            } else {
                shared.retainAll(lineage);
            }
        }
        if (shared == null || shared.isEmpty()) {
            return null;
        }
        String name = shared.iterator().next();
        return unit.entities().stream().filter(e -> e.name().equals(name)).findFirst().orElse(null);
    }

    /** {@code entity}'s name, then its parent's, up to its root, in that order. */
    private static Set<String> lineage(Entity entity, UnitDescription unit) {
        Set<String> names = new LinkedHashSet<>();
        Entity at = entity;
        while (at != null && names.add(at.name())) {
            String parent = at.superEntity();
            at = parent == null ? null : unit.entities().stream().filter(e -> e.name().equals(parent)).findFirst()
                    .orElse(null);
        }
        return names;
    }

    /** The rows of {@code found}, entities, as the values of {@code projection}, in the order found. */
    private static Table expanded(List<?> found, Projection projection, EntityManager em,
                                  Function<Object, Object> ids) {
        List<Object> keys = found.stream().map(ids).toList();
        // keyed by text: a cell writes a UUID id as text, and a number id the same whatever its type
        Map<String, List<Object>> byId = new HashMap<>();
        String jpql = projection.select() + " WHERE " + projection.idPath() + " IN :ids";
        for (int from = 0; from < keys.size(); from += CHUNK) {
            List<Object> chunk = List.copyOf(keys.subList(from, Math.min(keys.size(), from + CHUNK)));
            Table chunked = projected(projection, em.createQuery(jpql).setParameter("ids", chunk).getResultList());
            chunked.rows().forEach(row -> byId.put(String.valueOf(row.get(projection.idColumn())), row));
        }
        List<List<Object>> rows = keys.stream().map(key -> byId.get(String.valueOf(key))).filter(Objects::nonNull)
                .toList();
        return new Table(projection.columns(), rows);
    }

    private static List<Object> cells(Object[] values, int width, Map<String, Entity> byClass,
                                      Function<Object, Object> ids) {
        List<Object> cells = new ArrayList<>(width);
        for (int i = 0; i < width; i++) {
            cells.add(i < values.length ? cell(values[i], byClass, ids) : null);
        }
        return cells;
    }

    /** A value as a table of rows writes it: a number, a boolean or text; an entity as {@code Note#3}. */
    private static Object cell(Object value, Map<String, Entity> byClass, Function<Object, Object> ids) {
        return switch (value) {
            case null -> null;
            case Boolean flag -> flag;
            case Number number -> number;
            case String text -> text;
            case Enum<?> constant -> constant.name();
            case byte[] bytes -> bytes.length + " bytes";
            default -> {
                Entity entity = byClass.get(value.getClass().getName());
                yield entity == null ? String.valueOf(value) : entity.name() + "#" + ids.apply(value);
            }
        };
    }

    /** The columns {@code names}, each typed by its first value that is not {@code null}. */
    private static List<Column> columns(List<String> names, List<List<Object>> rows) {
        List<Column> columns = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            int column = i;
            Object first = rows.stream().map(row -> row.get(column)).filter(Objects::nonNull).findFirst()
                    .orElse(null);
            columns.add(new Column(names.get(i), typeOf(first)));
        }
        return columns;
    }

    private static String typeOf(Object value) {
        return switch (value) {
            case null -> "";
            case Long _, Integer _, Short _, Byte _, BigInteger _ -> "integer";
            case Number _ -> "number";
            case Boolean _ -> "boolean";
            default -> "string";
        };
    }
}
