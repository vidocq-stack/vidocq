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
package io.vidocq.mansart.data.processor;

import io.vidocq.mansart.data.dialect.SqlNames;

import javax.annotation.processing.Filer;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The tables an application's Flyway SQL migrations create (MANSART-037), read once per compilation so the processor
 * can warn about an entity whose table none of them creates. Maven copies {@code src/main/resources} to the class
 * output before compiling, so the default location, Flyway's {@code classpath:db/migration}, is
 * {@code <class output>/db/migration}; {@code -Amansart.migrations=<dir>[,<dir>…]} names others.
 *
 * <p>Only {@code CREATE TABLE}, {@code CREATE VIEW} and {@code ALTER TABLE … RENAME TO} are read, comments and string
 * literals skipped. A quoted name must match exactly, an unquoted one in any case (databases fold unquoted names).</p>
 */
final class MigrationTables {

    /** One table name as a migration spells it. */
    private record Table(String name, boolean quoted) {

        boolean matches(String wanted) {
            return quoted ? name.equals(wanted) : name.equalsIgnoreCase(wanted);
        }
    }

    private final List<Table> tables;
    private final String where;

    private MigrationTables(List<Table> tables, String where) {
        this.tables = tables;
        this.where = where;
    }

    /**
     * The tables of the migrations under {@code locations} (or {@code <class output>/db/migration}), or empty when
     * there is no SQL migration there: the check then has nothing to compare with.
     */
    static Optional<MigrationTables> read(Filer filer, String locations) {
        List<Path> dirs = new ArrayList<>();
        if (locations != null && !locations.isBlank()) {
            for (String location : locations.split(",")) {
                dirs.add(Path.of(location.strip()));
            }
        } else {
            classOutput(filer).ifPresent(out -> dirs.add(out.resolve("db").resolve("migration")));
        }
        List<Table> tables = new ArrayList<>();
        boolean any = false;
        for (Path dir : dirs) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            for (Path sql : sqlFiles(dir)) {
                any = true;
                collect(read(sql), tables);
            }
        }
        String where = locations != null && !locations.isBlank() ? locations.strip() : "db/migration";
        return any ? Optional.of(new MigrationTables(List.copyOf(tables), where)) : Optional.empty();
    }

    /** Whether a migration creates {@code table} (Mansart's name for it, unquoted in the message). */
    boolean creates(String table) {
        return tables.stream().anyMatch(t -> t.matches(table));
    }

    /** Where the migrations were read, for the message. */
    String where() {
        return where;
    }

    /**
     * A table a migration creates that looks made for {@code simpleClassName}: named after the class itself
     * ({@code book} for {@code Book}) or after its snake_case singular; empty when there is none.
     */
    Optional<String> madeAfterTheClassName(String simpleClassName) {
        String snake = SqlNames.tableName(simpleClassName);
        String singular = SqlNames.toSnakeCase(simpleClassName);
        return tables.stream()
                .filter(t -> t.matches(simpleClassName) || t.matches(singular))
                .map(t -> t.name().toLowerCase(Locale.ROOT).equals(snake) ? null : t.name())
                .filter(n -> n != null)
                .findFirst();
    }

    /** Reads the {@code CREATE TABLE|VIEW} and {@code ALTER TABLE … RENAME TO} statements of {@code sql}. */
    private static void collect(String sql, List<Table> tables) {
        List<String> tokens = tokens(sql);
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (token.equalsIgnoreCase("CREATE")) {
                int j = i + 1;
                while (j < tokens.size() && MODIFIERS.contains(tokens.get(j).toUpperCase(Locale.ROOT))) {
                    j++;
                }
                if (j < tokens.size() && (tokens.get(j).equalsIgnoreCase("TABLE") || tokens.get(j).equalsIgnoreCase("VIEW"))) {
                    nameAt(tokens, skipIfNotExists(tokens, j + 1)).ifPresent(tables::add);
                }
            } else if (token.equalsIgnoreCase("RENAME") && i + 1 < tokens.size()
                    && tokens.get(i + 1).equalsIgnoreCase("TO")) {
                nameAt(tokens, i + 2).ifPresent(tables::add);
            }
        }
    }

    /** The words that may sit between {@code CREATE} and {@code TABLE}. */
    private static final java.util.Set<String> MODIFIERS = java.util.Set.of(
            "OR", "REPLACE", "GLOBAL", "LOCAL", "TEMP", "TEMPORARY", "UNLOGGED", "CACHED", "MEMORY");

    private static int skipIfNotExists(List<String> tokens, int at) {
        if (at + 2 < tokens.size() && tokens.get(at).equalsIgnoreCase("IF")
                && tokens.get(at + 1).equalsIgnoreCase("NOT") && tokens.get(at + 2).equalsIgnoreCase("EXISTS")) {
            return at + 3;
        }
        return at;
    }

    /** The table of a possibly schema-qualified name starting at {@code at}: its last part. */
    private static Optional<Table> nameAt(List<String> tokens, int at) {
        if (at >= tokens.size() || !isName(tokens.get(at))) {
            return Optional.empty();
        }
        int last = at;
        while (last + 2 < tokens.size() && tokens.get(last + 1).equals(".") && isName(tokens.get(last + 2))) {
            last += 2;
        }
        return Optional.of(table(tokens.get(last)));
    }

    private static boolean isName(String token) {
        char first = token.charAt(0);
        return first == '"' || first == '`' || first == '[' || Character.isLetter(first) || first == '_';
    }

    /**
     * The words, quoted names and dots of {@code sql}, comments and literals left out: enough to read the names a
     * statement creates.
     */
    private static List<String> tokens(String sql) {
        List<String> tokens = new ArrayList<>();
        int i = 0;
        while (i < sql.length()) {
            i = next(sql, i, tokens);
        }
        return tokens;
    }

    /** Reads what starts at {@code i}, adds its token if it has one, and returns where the next one starts. */
    private static int next(String sql, int i, List<String> tokens) {
        char c = sql.charAt(i);
        if (sql.startsWith("--", i)) {
            return after(sql, "\n", i + 2);
        }
        if (sql.startsWith("/*", i)) {
            return after(sql, "*/", i + 2);
        }
        if (c == '\'') {
            return after(sql, "'", i + 1);
        }
        if (c == '"' || c == '`' || c == '[') {
            int stop = after(sql, c == '[' ? "]" : String.valueOf(c), i + 1);
            tokens.add(sql.substring(i, stop));
            return stop;
        }
        if (Character.isLetter(c) || c == '_') {
            int stop = i;
            while (stop < sql.length() && isWordPart(sql.charAt(stop))) {
                stop++;
            }
            tokens.add(sql.substring(i, stop));
            return stop;
        }
        if (c == '.') {
            tokens.add(".");
        }
        return i + 1;
    }

    /** The index just past the next {@code end} from {@code from}, or the end of {@code sql}. */
    private static int after(String sql, String end, int from) {
        int at = sql.indexOf(end, from);
        return at < 0 ? sql.length() : at + end.length();
    }

    private static boolean isWordPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    private static Table table(String ident) {
        char first = ident.charAt(0);
        if (first == '"' || first == '`' || first == '[') {
            return new Table(ident.substring(1, ident.length() - 1), true);
        }
        return new Table(ident, false);
    }

    private static Optional<Path> classOutput(Filer filer) {
        try {
            URI uri = filer.getResource(StandardLocation.CLASS_OUTPUT, "", "mansart-migration-check").toUri();
            return "file".equals(uri.getScheme()) ? Optional.of(Path.of(uri).getParent()) : Optional.empty();
        } catch (IOException | IllegalArgumentException _) {
            return Optional.empty();
        }
    }

    private static List<Path> sqlFiles(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".sql"))
                    .sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path sql) {
        try {
            return Files.readString(sql, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
