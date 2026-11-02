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

import io.vidocq.mansart.data.core.EntityModels;
import io.vidocq.mansart.data.core.Generated;
import io.vidocq.mansart.data.core.MansartData;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MANSART-038: the generated {@code _MansartComponents} comes first, and the reflective path stays the fallback. Without
 * it (a portable container that does not load the service, an older build), Mansart still reads the entity model and
 * creates the repository implementation by reflection, and builds projection records through their constructor.
 */
class GeneratedComponentsFallbackTest {

    @TempDir
    Path out;

    private Path compileShop() throws IOException {
        var result = InProcessCompilation.compileSources(out,
                "shop.Book", """
                        package shop;

                        @jakarta.persistence.Entity
                        public class Book {
                            @jakarta.persistence.Id
                            public Long id;
                            public String title;
                        }
                        """,
                "shop.Books", """
                        package shop;

                        import jakarta.data.repository.Query;
                        import java.util.List;

                        @jakarta.data.repository.Repository
                        public interface Books extends jakarta.data.repository.BasicRepository<Book, Long> {
                            @Query("SELECT id, title FROM Book")
                            List<Row> rows();

                            record Row(Long id, String title) {}
                        }
                        """);
        assertThat(result.errors()).isEmpty();
        return result.classes();
    }

    private static MansartData h2(String name) throws SQLException {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE \"books\" (\"id\" BIGINT PRIMARY KEY, \"title\" VARCHAR(100))");
        }
        return MansartData.builder().dataSource(ds).build();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> saveAndProject(MansartData data, Class<?> booksType, Class<?> bookType)
            throws ReflectiveOperationException {
        Object books = data.repository(booksType);
        Object book = bookType.getConstructor().newInstance();
        bookType.getField("id").set(book, 1L);
        bookType.getField("title").set(book, "Vidocq");
        booksType.getMethod("save", Object.class).invoke(books, book);
        return (List<Object>) booksType.getMethod("rows").invoke(books);
    }

    @Test
    void withTheGeneratedComponentsMansartUsesThem() throws Exception {
        Path classes = compileShop();
        try (var loader = new URLClassLoader(new URL[] {classes.toUri().toURL()}, getClass().getClassLoader())) {
            Class<?> book = loader.loadClass("shop.Book");
            Class<?> books = loader.loadClass("shop.Books");

            assertThat(Generated.model(book)).isNotNull();
            assertThat(saveAndProject(h2("generated-components"), books, book))
                    .singleElement().hasToString("Row[id=1, title=Vidocq]");
        }
    }

    @Test
    void withoutTheGeneratedComponentsTheReflectiveFallbackStillWorks() throws Exception {
        Path classes = compileShop();
        Files.delete(classes.resolve("shop/_MansartComponents.class"));
        Files.delete(classes.resolve("META-INF/services/io.vidocq.mansart.data.core.GeneratedComponents"));
        try (var loader = new URLClassLoader(new URL[] {classes.toUri().toURL()}, getClass().getClassLoader())) {
            Class<?> book = loader.loadClass("shop.Book");
            Class<?> books = loader.loadClass("shop.Books");

            assertThat(Generated.model(book)).isNull();
            assertThat(Generated.repository(books, h2("no-components-probe").runtime())).isNull();
            assertThat(EntityModels.of(book)).isNotNull();
            assertThat(saveAndProject(h2("no-generated-components"), books, book))
                    .singleElement().hasToString("Row[id=1, title=Vidocq]");
        }
    }

    /** The run-time path has no generated record factory: it still builds the record through its constructor. */
    @Test
    void theRunTimePathStillBuildsProjectionRecordsByReflection() throws Exception {
        Path classes = compileShop();
        try (var loader = new URLClassLoader(new URL[] {classes.toUri().toURL()}, getClass().getClassLoader())) {
            Class<?> book = loader.loadClass("shop.Book");
            Class<?> books = loader.loadClass("shop.Books");
            MansartData data = h2("run-time-records");
            Object repository = data.runtimeRepository(books);
            Object one = book.getConstructor().newInstance();
            book.getField("id").set(one, 1L);
            book.getField("title").set(one, "Vidocq");
            books.getMethod("save", Object.class).invoke(repository, one);

            assertThat((List<?>) books.getMethod("rows").invoke(repository))
                    .singleElement().hasToString("Row[id=1, title=Vidocq]");
        }
    }
}
