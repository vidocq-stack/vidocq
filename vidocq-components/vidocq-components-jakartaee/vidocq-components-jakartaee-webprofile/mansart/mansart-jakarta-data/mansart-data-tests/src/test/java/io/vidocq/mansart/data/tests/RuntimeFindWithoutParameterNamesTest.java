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

import io.vidocq.mansart.data.core.MansartData;
import io.vidocq.mansart.data.core.MansartDataException;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The run-time path on a repository compiled without {@code -parameters} (mansart#40): a {@code @Find} parameter the
 * implementation cannot bind to an attribute — no name, and several attributes of its type — must fail with the
 * method's name, never run a query that silently drops the parameter and returns every row.
 */
class RuntimeFindWithoutParameterNamesTest {

    private static final String BOOK_SOURCE = """
            package lib;

            import jakarta.persistence.Entity;
            import jakarta.persistence.Id;

            @Entity
            public class Book {
                @Id public Long id;
                public String title;
                public String author;
            }
            """;

    private static final String BOOKS_SOURCE = """
            package lib;

            import jakarta.data.repository.BasicRepository;
            import jakarta.data.repository.Find;
            import jakarta.data.repository.Repository;
            import java.util.List;

            @Repository
            public interface Books extends BasicRepository<Book, Long> {
                @Find
                List<Book> byAuthor(String author);
            }
            """;

    private static final String KEYS_SOURCE = """
            package lib;

            import jakarta.data.repository.BasicRepository;
            import jakarta.data.repository.Find;
            import jakarta.data.repository.Repository;
            import java.util.List;

            @Repository
            public interface Keys extends BasicRepository<Book, Long> {
                @Find
                List<Book> byKey(Long key);
            }
            """;

    @TempDir
    Path out;

    private static Object repository(ClassLoader loader, String name) throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:runtime-find-nameless;DB_CLOSE_DELAY=-1");
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS \"books\" (\"id\" BIGINT PRIMARY KEY, \"title\" VARCHAR(200),"
                    + " \"author\" VARCHAR(200))");
            s.execute("DELETE FROM \"books\"");
            s.execute("INSERT INTO \"books\" VALUES (1, 'Germinal', 'Zola'), (2, 'Nana', 'Zola'),"
                    + " (3, 'Candide', 'Voltaire')");
        }
        return MansartData.builder().dataSource(ds).build().runtimeRepository(loader.loadClass(name));
    }

    private static Object call(Object books, String method, Class<?> type, Object arg) throws Throwable {
        try {
            return books.getClass().getMethod(method, type).invoke(books, arg);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @Test
    void aParameterThatMatchesNoAttributeFailsNamingTheMethod() throws Exception {
        var library = InProcessCompilation.compileLibrary(out, false, "lib.Book", BOOK_SOURCE, "lib.Books",
                BOOKS_SOURCE);
        assertThat(library.success()).as(library.errors().toString()).isTrue();
        try (var loader = new URLClassLoader(new URL[] {library.classes().toUri().toURL()},
                getClass().getClassLoader())) {
            // Refused when the repository is created, at start-up, not at the first call.
            assertThatThrownBy(() -> repository(loader, "lib.Books"))
                    .isInstanceOf(MansartDataException.class)
                    .hasMessageContaining("lib.Books.byAuthor")
                    .hasMessageContaining("@By")
                    .hasMessageContaining("-parameters");
        }
    }

    @Test
    void aParameterOfAUniqueAttributeTypeStillBindsByType() throws Throwable {
        var library = InProcessCompilation.compileLibrary(out, false, "lib.Book", BOOK_SOURCE, "lib.Keys",
                KEYS_SOURCE);
        try (var loader = new URLClassLoader(new URL[] {library.classes().toUri().toURL()},
                getClass().getClassLoader())) {
            Object keys = repository(loader, "lib.Keys");

            assertThat((List<?>) call(keys, "byKey", Long.class, 3L)).hasSize(1);
        }
    }
}
