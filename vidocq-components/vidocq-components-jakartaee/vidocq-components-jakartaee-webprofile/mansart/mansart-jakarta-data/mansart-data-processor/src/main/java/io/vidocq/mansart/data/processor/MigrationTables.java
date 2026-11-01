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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The tables an application's Flyway SQL migrations create (MANSART-037), read once per compilation so the processor
 * can warn about an entity whose table none of them creates. Maven copies {@code src/main/resources} to the class
 * output before compiling, so the default location, Flyway's {@code classpath:db/migration}, is
 * {@code <class output>/db/migration}; {@code -Amansart.migrations=<dir>[,<dir>…]} names others.
 *
 * <p>Only {@code CREATE TABLE}, {@code CREATE VIEW} and {@code ALTER TABLE … RENAME TO} are read, comments skipped. A
 * quoted name must match exactly, an unquoted one in any case (databases fold unquoted names).</p>
 */
final class MigrationTables {

    private static final String IDENT = "(\"[^\"]+\"|`[^`]+`|\\[[^\\]]+\\]|[A-Za-z_][A-Za-z0-9_$]*)";
    private static final String NAME = IDENT + "(?:\\s*\\.\\s*" + IDENT + ")?";
    private static final Pattern CREATE = Pattern.compile("\\bCREATE\\s+(?:OR\\s+REPLACE\\s+)?"
            + "(?:(?:GLOBAL|LOCAL|TEMP|TEMPORARY|UNLOGGED|CACHED|MEMORY)\\s+)*(?:TABLE|VIEW)\\s+"
            + "(?:IF\\s+NOT\\s+EXISTS\\s+)?" + NAME, Pattern.CASE_INSENSITIVE);
    private static final Pattern RENAME = Pattern.compile("\\bALTER\\s+TABLE\\s+(?:IF\\s+EXISTS\\s+)?" + NAME
            + "\\s+RENAME\\s+TO\\s+" + NAME, Pattern.CASE_INSENSITIVE);
    private static final Pattern COMMENTS = Pattern.compile("--[^\\n]*|/\\*.*?\\*/", Pattern.DOTALL);

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
        String singular = snakeSingular(simpleClassName);
        return tables.stream()
                .filter(t -> t.matches(simpleClassName) || t.matches(singular))
                .map(t -> t.name().toLowerCase(Locale.ROOT).equals(snake) ? null : t.name())
                .filter(n -> n != null)
                .findFirst();
    }

    private static String snakeSingular(String camel) {
        StringBuilder sb = new StringBuilder(camel.length() + 4);
        for (int i = 0; i < camel.length(); i++) {
            char c = camel.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    sb.append('_');
                }
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static void collect(String sql, List<Table> tables) {
        String text = COMMENTS.matcher(sql).replaceAll(" ");
        Matcher create = CREATE.matcher(text);
        while (create.find()) {
            tables.add(table(create.group(2) != null ? create.group(2) : create.group(1)));
        }
        Matcher rename = RENAME.matcher(text);
        while (rename.find()) {
            tables.add(table(rename.group(3)));
        }
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
        } catch (IOException | IllegalArgumentException e) {
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
