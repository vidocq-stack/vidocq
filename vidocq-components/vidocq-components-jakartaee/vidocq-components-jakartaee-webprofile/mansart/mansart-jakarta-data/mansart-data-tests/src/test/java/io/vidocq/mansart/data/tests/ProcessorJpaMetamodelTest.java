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
 * The JPA canonical metamodel {@code Book_} belongs to the persistence provider's processor (Noether, Hibernate
 * Processor…), which knows the kind of every attribute: Mansart writes its Jakarta Data metamodel {@code _Book} only
 * (Vidocq/noether#45, F15).
 */
class ProcessorJpaMetamodelTest {

    private static final String BOOK = "shop.Book";
    private static final String BOOK_SOURCE = """
            package shop;

            import jakarta.persistence.Entity;
            import jakarta.persistence.GeneratedValue;
            import jakarta.persistence.Id;

            @Entity
            public class Book {
                @Id @GeneratedValue public Long id;
                public String title;
            }
            """;
    private static final String BOOKS = "shop.Books";
    private static final String BOOKS_SOURCE = """
            package shop;

            import jakarta.data.repository.BasicRepository;
            import jakarta.data.repository.Repository;
            import java.util.List;

            @Repository
            public interface Books extends BasicRepository<Book, Long> {
                List<Book> findByTitle(String title);
            }
            """;

    @TempDir
    Path out;

    @Test
    void aJpaEntityGetsTheDataMetamodelOnly() throws IOException {
        InProcessCompilation.Result result = InProcessCompilation.compileSources(out, BOOK, BOOK_SOURCE, BOOKS,
                BOOKS_SOURCE);

        assertThat(result.success()).as(result.errors().toString()).isTrue();
        assertThat(result.generatedSource("shop._Book")).contains("class _Book");
        assertThat(result.generated().resolve("shop/Book_.java")).doesNotExist();
        assertThat(result.generatedSource("shop.BooksImpl")).contains("class BooksImpl");
    }

    /**
     * The persistence provider's processor writes {@code Book_} in the same compilation, with a plural attribute
     * Mansart has no kind for: Mansart leaves the type to it instead of writing a second one.
     */
    @Test
    void theJpaMetamodelOfThePersistenceProviderIsLeftToIt() throws IOException {
        InProcessCompilation.Result result = InProcessCompilation.compileSources(out, BOOK, BOOK_SOURCE, BOOKS,
                BOOKS_SOURCE, "shop.Book_", """
                package shop;

                import jakarta.persistence.metamodel.ListAttribute;
                import jakarta.persistence.metamodel.SingularAttribute;
                import jakarta.persistence.metamodel.StaticMetamodel;

                @StaticMetamodel(Book.class)
                public abstract class Book_ {
                    public static volatile SingularAttribute<Book, Long> id;
                    public static volatile SingularAttribute<Book, String> title;
                    public static volatile ListAttribute<Book, String> tags;
                }
                """);

        assertThat(result.success()).as(result.errors().toString()).isTrue();
        assertThat(result.errors()).isEmpty();
        assertThat(result.generated().resolve("shop/Book_.java")).doesNotExist();
        assertThat(result.generatedSource("shop._Book")).contains("class _Book");
    }
}
