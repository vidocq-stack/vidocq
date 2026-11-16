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

import io.vidocq.noether.jpa.core.spi.UnitDescription.Index;
import io.vidocq.noether.jpa.core.spi.UnitDescription.Table;
import io.vidocq.runtime.spi.devconsole.PanelAction.ActionResult.Column;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.function.BiPredicate;

/**
 * The indexes a unit declares against those its database has, by table, case-insensitively, since a database stores a
 * name unquoted in its own case. A declared index is matched first to the found index of its name and its columns,
 * then to one of its columns, then to one of its name; it is {@value #SAME} when the columns and the uniqueness are
 * equal, {@value #DIFFERENT} otherwise, {@value #MISSING} when nothing matches. A found index nothing matches is
 * {@value #EXTRA}, unless it is the index of the table's primary key or of one of its foreign keys, which a database
 * creates by itself.
 */
final class IndexDiff {

    static final String SAME = "same";
    static final String MISSING = "missing in the database";
    static final String EXTRA = "extra in the database";
    static final String DIFFERENT = "different";

    /** The columns of a comparison's rows. */
    static final List<Column> COLUMNS = List.of(new Column("table", "string"), new Column("index", "string"),
            new Column("mapping", "string"), new Column("database", "string"), new Column("status", "string"));

    /**
     * An index of the database.
     *
     * @param table   the table, as the unit names it
     * @param name    its name, as the database stores it
     * @param columns its columns, in order
     * @param unique  whether it is unique
     */
    record Found(String table, String name, List<String> columns, boolean unique) {

        Found {
            columns = List.copyOf(columns);
        }
    }

    /** An index the unit declares, with its table. */
    private record Declared(Table table, Index index) {}

    private static final List<BiPredicate<Declared, Found>> PASSES = List.of(
            (declared, found) -> sameName(declared, found) && sameColumns(declared.index().columns(), found.columns()),
            (declared, found) -> sameColumns(declared.index().columns(), found.columns()),
            IndexDiff::sameName);

    private IndexDiff() {
    }

    /**
     * A row per declared index, in the unit's order, then per extra index: {@code table}, {@code index},
     * {@code mapping} and {@code database}, each {@code unique (A, B)} or {@code (A, B)} or empty, and
     * {@code status}.
     */
    static List<List<String>> rows(List<Table> declared, List<Found> found) {
        List<Found> left = new ArrayList<>(found);
        List<Declared> indexes = declared.stream()
                .flatMap(table -> table.indexes().stream().map(index -> new Declared(table, index))).toList();
        Found[] matches = new Found[indexes.size()];
        for (BiPredicate<Declared, Found> pass : PASSES) {
            for (int i = 0; i < matches.length; i++) {
                if (matches[i] == null) {
                    matches[i] = take(left, indexes.get(i), pass);
                }
            }
        }
        left.removeIf(index -> ofAKey(index, declared));
        List<List<String>> rows = new ArrayList<>();
        for (int i = 0; i < matches.length; i++) {
            rows.add(row(indexes.get(i), matches[i]));
        }
        left.forEach(extra -> rows.add(List.of(extra.table(), extra.name(), "", shape(extra.columns(),
                extra.unique()), EXTRA)));
        return rows;
    }

    /** {@code 2 same, 1 missing in the database}: how many rows have each status, those with none left out. */
    static String summary(List<List<String>> rows) {
        List<String> counts = new ArrayList<>();
        for (String status : List.of(SAME, MISSING, EXTRA, DIFFERENT)) {
            long n = rows.stream().filter(row -> row.getLast().equals(status)).count();
            if (n > 0) {
                counts.add(n + " " + status);
            }
        }
        return counts.isEmpty() ? "no index declared or found" : String.join(", ", counts);
    }

    private static List<String> row(Declared declared, Found match) {
        Index index = declared.index();
        String name = index.name() != null ? index.name() : "";
        if (index.name() == null && match != null) {
            name = match.name();
        }
        String status = MISSING;
        if (match != null) {
            status = sameColumns(index.columns(), match.columns()) && index.unique() == match.unique()
                    ? SAME : DIFFERENT;
        }
        return List.of(declared.table().name(), name, shape(index.columns(), index.unique()),
                match == null ? "" : shape(match.columns(), match.unique()), status);
    }

    /** Takes from {@code left} the first index of {@code declared}'s table that {@code pass} matches. */
    private static Found take(List<Found> left, Declared declared, BiPredicate<Declared, Found> pass) {
        for (Iterator<Found> candidates = left.iterator(); candidates.hasNext(); ) {
            Found candidate = candidates.next();
            if (same(candidate.table(), declared.table().name()) && pass.test(declared, candidate)) {
                candidates.remove();
                return candidate;
            }
        }
        return null;
    }

    /** Whether {@code found} is the index of a declared table's primary key or of one of its foreign keys. */
    private static boolean ofAKey(Found found, List<Table> declared) {
        return declared.stream().filter(table -> same(found.table(), table.name())).anyMatch(table ->
                sameColumns(table.primaryKey(), found.columns())
                        || table.foreignKeys().stream().anyMatch(key -> sameColumns(key.columns(), found.columns())));
    }

    private static boolean sameName(Declared declared, Found found) {
        return declared.index().name() != null && same(declared.index().name(), found.name());
    }

    private static boolean sameColumns(List<String> a, List<String> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!same(a.get(i), b.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean same(String a, String b) {
        return a != null && b != null && unquoted(a).equalsIgnoreCase(unquoted(b));
    }

    private static String unquoted(String name) {
        String stripped = name.strip();
        boolean quoted = stripped.length() > 1 && "\"`".indexOf(stripped.charAt(0)) >= 0
                && stripped.charAt(stripped.length() - 1) == stripped.charAt(0);
        return (quoted ? stripped.substring(1, stripped.length() - 1) : stripped).toLowerCase(Locale.ROOT);
    }

    private static String shape(List<String> columns, boolean unique) {
        return (unique ? "unique " : "") + "(" + String.join(", ", columns) + ")";
    }
}
