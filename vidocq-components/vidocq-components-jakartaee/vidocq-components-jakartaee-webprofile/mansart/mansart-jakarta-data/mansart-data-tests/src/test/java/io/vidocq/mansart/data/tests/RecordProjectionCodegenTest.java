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
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MANSART-038: a JDQL projection into a record is built by code the processor generates in the application's module,
 * not through the record's constructor by reflection from Mansart's module — under the Java module system that needed
 * the record's package exported or opened to Mansart.
 */
class RecordProjectionCodegenTest {

    @TempDir
    Path out;

    @Test
    void theGeneratedRepositoryBuildsTheRecordsItself() throws IOException {
        var result = InProcessCompilation.compileSources(out,
                "demo.Book", """
                        package demo;

                        import jakarta.persistence.Entity;
                        import jakarta.persistence.Id;

                        @Entity
                        public class Book {
                            @Id
                            public Long id;
                            public String title;
                            public int pages;
                        }
                        """,
                "demo.Books", """
                        package demo;

                        import jakarta.data.repository.BasicRepository;
                        import jakarta.data.repository.Query;
                        import jakarta.data.repository.Repository;
                        import java.util.List;

                        @Repository
                        public interface Books extends BasicRepository<Book, Long> {
                            @Query("SELECT id, title, pages FROM Book")
                            List<Summary> summaries();

                            @Query("SELECT title FROM Book")
                            List<Title> titles();

                            record Summary(Long id, String title, int pages) {}

                            record Title(String title) {}
                        }
                        """);

        assertThat(result.errors()).isEmpty();
        String impl = result.generatedSource("demo.BooksImpl");
        assertThat(impl)
                .contains("_r -> new demo.Books.Summary((java.lang.Long) _r[0], (java.lang.String) _r[1], "
                        + "(java.lang.Integer) _r[2])")
                .contains("_r -> new demo.Books.Title((java.lang.String) _r[0])");
    }

    /**
     * MANSART-038: each package with entities or repositories gets a generated {@code _MansartComponents}, a service
     * that hands Mansart the entity models and creates the repository implementations by plain calls.
     */
    @Test
    void eachPackageGetsAComponentsServiceDeclaredInMetaInfServices() throws IOException {
        var result = InProcessCompilation.compileSources(out,
                "demo.Book", """
                        package demo;

                        @jakarta.persistence.Entity
                        public class Book {
                            @jakarta.persistence.Id
                            public Long id;
                            public String title;
                        }
                        """,
                "demo.Books", """
                        package demo;

                        @jakarta.data.repository.Repository
                        public interface Books extends jakarta.data.repository.BasicRepository<Book, Long> {
                        }
                        """);

        assertThat(result.errors()).isEmpty();
        assertThat(result.generatedSource("demo._MansartComponents"))
                .contains("implements io.vidocq.mansart.data.core.GeneratedComponents")
                .contains("if (entity == demo.Book.class) return (io.vidocq.mansart.data.dialect.EntityModel<T>) (io.vidocq.mansart.data.dialect.EntityModel<?>) demo._Book.$MODEL;")
                .contains("if (repository == demo.Books.class) return new demo.BooksImpl(runtime);");
        assertThat(java.nio.file.Files.readString(result.classes()
                .resolve("META-INF/services/io.vidocq.mansart.data.core.GeneratedComponents")))
                .contains("demo._MansartComponents");
    }
}
