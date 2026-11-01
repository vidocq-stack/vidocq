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
package io.vidocq.mansart.data.tests;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MANSART-037: when the application ships Flyway SQL migrations, the processor warns, at build time, about an entity
 * whose table no migration creates — the usual cause being a table made after the class name ({@code book}) while
 * Mansart looks for {@code books}. A warning, not an error: the database may be managed elsewhere.
 */
class MigrationTableCheckTest {

    private static final String BOOK = "demo.Book";

    @TempDir
    Path out;

    private static String book(String tableAnnotation) {
        return """
                package demo;

                import jakarta.persistence.Entity;
                import jakarta.persistence.Id;
                import jakarta.persistence.Table;

                @Entity
                %s
                public class Book {
                    @Id
                    public Long id;
                    public String title;
                }
                """.formatted(tableAnnotation);
    }

    private void migration(String file, String sql) throws IOException {
        Path dir = Files.createDirectories(out.resolve("classes/db/migration"));
        Files.writeString(dir.resolve(file), sql, StandardCharsets.UTF_8);
    }

    private List<String> warnings(List<String> options, String tableAnnotation) throws IOException {
        var result = InProcessCompilation.compileSources(out, options, BOOK, book(tableAnnotation));
        assertThat(result.errors()).isEmpty();
        return result.warnings().stream().filter(w -> w.contains("[mansart-data]")).toList();
    }

    private List<String> warnings(String tableAnnotation) throws IOException {
        return warnings(List.of(), tableAnnotation);
    }

    @Test
    void aTableMadeAfterTheClassNameIsReportedWithTheFix() throws IOException {
        migration("V1__books.sql", "CREATE TABLE book (id BIGINT PRIMARY KEY, title VARCHAR(200));");

        List<String> warnings = warnings("");

        assertThat(warnings).hasSize(1);
        assertThat(warnings.getFirst())
                .contains(BOOK, "\"books\"", "Book → books", "\"book\"", "@Table(name = \"book\")",
                        "mansart.migrationCheck=off");
    }

    @Test
    void theDefaultTableCreatedByAMigrationIsSilent() throws IOException {
        migration("V1__books.sql", "create table books (id bigint primary key, title varchar(200));");

        assertThat(warnings("")).isEmpty();
    }

    @Test
    void quotedSchemaQualifiedIfNotExistsAndUpperCaseNamesAreRecognised() throws IOException {
        migration("V1__a.sql", """
                -- a comment naming CREATE TABLE nothing
                /* CREATE TABLE ghost (id INT); */
                CREATE TABLE IF NOT EXISTS public."books" (id BIGINT);
                """);
        assertThat(warnings("")).isEmpty();

        migration("V1__a.sql", "CREATE TABLE PUBLIC.BOOKS (ID BIGINT);");
        assertThat(warnings("")).isEmpty();
    }

    @Test
    void backQuotedBracketedAndSpacedNamesAreRecognised() throws IOException {
        migration("V1__a.sql", "CREATE TABLE `books` (id BIGINT);");
        assertThat(warnings("")).isEmpty();

        migration("V1__a.sql", "CREATE TABLE [books] (id BIGINT);");
        assertThat(warnings("")).isEmpty();

        migration("V1__a.sql", "create   table   app . books (id bigint);");
        assertThat(warnings("")).isEmpty();
    }

    @Test
    void viewsAndTableModifiersAreRecognised() throws IOException {
        migration("V1__a.sql", "CREATE OR REPLACE VIEW books AS SELECT 1;");
        assertThat(warnings("")).isEmpty();

        migration("V1__a.sql", "CREATE GLOBAL TEMPORARY TABLE books (id BIGINT);");
        assertThat(warnings("")).isEmpty();
    }

    @Test
    void literalsCommentsAndOtherStatementsCreateNoTable() throws IOException {
        migration("V1__a.sql", """
                INSERT INTO notes VALUES ('CREATE TABLE books (id INT)');
                CREATE INDEX books ON book (id);
                ALTER TABLE book RENAME COLUMN title TO books;
                -- CREATE TABLE books
                CREATE TABLE book (id BIGINT); /* CREATE TABLE books""");
        migration("notes.txt", "CREATE TABLE books (id BIGINT);");

        assertThat(warnings("")).hasSize(1);
    }

    @Test
    void aQuotedNameMatchesOnlyItsExactCase() throws IOException {
        migration("V1__a.sql", "CREATE TABLE \"BOOKS\" (id BIGINT);");

        assertThat(warnings("")).hasSize(1);
    }

    @Test
    void aRenameToAQuotedNameWithIfExistsCounts() throws IOException {
        migration("V1__a.sql", "CREATE TABLE book (id BIGINT); ALTER TABLE IF EXISTS book RENAME TO \"books\";");

        assertThat(warnings("")).isEmpty();
    }

    @Test
    void aTableRenamedByALaterMigrationCounts() throws IOException {
        migration("V1__book.sql", "CREATE TABLE book (id BIGINT);");
        migration("V2__rename.sql", "ALTER TABLE book RENAME TO books;");

        assertThat(warnings("")).isEmpty();
    }

    @Test
    void tableNamesTheTableTheMigrationCreates() throws IOException {
        migration("V1__book.sql", "CREATE TABLE book (id BIGINT);");

        assertThat(warnings("@Table(name = \"book\")")).isEmpty();
    }

    @Test
    void aWrongTableAnnotationIsReportedAsSuch() throws IOException {
        migration("V1__book.sql", "CREATE TABLE book (id BIGINT);");

        List<String> warnings = warnings("@Table(name = \"bok\")");

        assertThat(warnings).hasSize(1);
        assertThat(warnings.getFirst()).contains("\"bok\"", "@Table");
    }

    @Test
    void noMigrationMeansNoCheck() throws IOException {
        assertThat(warnings("")).isEmpty();
    }

    @Test
    void theCheckCanBeTurnedOff() throws IOException {
        migration("V1__book.sql", "CREATE TABLE book (id BIGINT);");

        assertThat(warnings(List.of("-Amansart.migrationCheck=off"), "")).isEmpty();
    }

    @Test
    void anotherMigrationFolderIsReadWhenNamed() throws IOException {
        Path elsewhere = Files.createDirectories(out.resolve("sql"));
        Files.writeString(elsewhere.resolve("V1__book.sql"), "CREATE TABLE book (id BIGINT);");

        assertThat(warnings(List.of("-Amansart.migrations=" + elsewhere), "")).hasSize(1);
    }
}
