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
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A plural attribute — {@code @ElementCollection}, {@code @OneToMany}, {@code @ManyToMany}, or a field whose type is a
 * collection or a map — is not a column of the entity's table: Mansart Data neither reads nor writes it, the
 * persistence provider does. The metamodel leaves it out, and still compiles (MANSART-049, Noether
 * BUG-20261111-06).
 */
class ProcessorPluralAttributeTest {

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
    void anElementCollectionIsNotAColumn() throws IOException {
        InProcessCompilation.Result result = InProcessCompilation.compileSources(out, "shop.Book", """
                package shop;

                import jakarta.persistence.ElementCollection;
                import jakarta.persistence.Entity;
                import jakarta.persistence.Id;
                import java.util.List;

                @Entity
                public class Book {
                    @Id public Long id;
                    public String title;
                    @ElementCollection public List<String> tags;
                }
                """, BOOKS, BOOKS_SOURCE);

        assertThat(result.success()).as(result.errors().toString()).isTrue();
        assertThat(result.generatedSource("shop._Book")).contains(" title = ").doesNotContain("tags");
        assertThat(result.generatedSource("shop.BooksImpl")).doesNotContain("tags");
        assertThat(result.diagnostics()).anySatisfy(d -> assertThat(d.getMessage(Locale.ROOT))
                .contains("shop.Book.tags", "Mansart Data neither reads nor writes it"));
    }

    @Test
    void aOneToManyAndAManyToManyAreNotColumns() throws IOException {
        InProcessCompilation.Result result = InProcessCompilation.compileSources(out, "shop.Author", """
                package shop;

                import jakarta.persistence.Entity;
                import jakarta.persistence.Id;
                import jakarta.persistence.ManyToMany;
                import jakarta.persistence.OneToMany;
                import java.util.List;
                import java.util.Set;

                @Entity
                public class Author {
                    @Id public Long id;
                    public String name;
                    @OneToMany(mappedBy = "author") public List<Book> books;
                    @ManyToMany public Set<Author> coAuthors;
                }
                """, "shop.Book", """
                package shop;

                import jakarta.persistence.Entity;
                import jakarta.persistence.Id;
                import jakarta.persistence.ManyToOne;

                @Entity
                public class Book {
                    @Id public Long id;
                    public String title;
                    @ManyToOne public Author author;
                }
                """, BOOKS, BOOKS_SOURCE);

        assertThat(result.success()).as(result.errors().toString()).isTrue();
        assertThat(result.generatedSource("shop._Author")).contains(" name = ")
                .doesNotContain("books").doesNotContain("coAuthors");
        assertThat(result.generatedSource("shop._Book")).contains(" author = ");
    }

    /** A derived query cannot compare a plural attribute: the build fails naming the method, not in generated code. */
    @Test
    void aDerivedQueryOnAPluralAttributeIsAnErrorNamingTheMethod() throws IOException {
        InProcessCompilation.Result result = InProcessCompilation.compileSources(out, "shop.Book", """
                package shop;

                import jakarta.persistence.ElementCollection;
                import jakarta.persistence.Entity;
                import jakarta.persistence.Id;
                import java.util.List;

                @Entity
                public class Book {
                    @Id public Long id;
                    public String title;
                    @ElementCollection public List<String> tags;
                }
                """, BOOKS, """
                package shop;

                import jakarta.data.repository.BasicRepository;
                import jakarta.data.repository.Repository;
                import java.util.List;

                @Repository
                public interface Books extends BasicRepository<Book, Long> {
                    List<Book> findByTags(String tag);
                }
                """);

        assertThat(result.errors()).isNotEmpty().allSatisfy(error -> assertThat(error).contains("findByTags"));
        assertThat(result.generatedSource("shop.BooksImpl")).doesNotContain("_Book.tags");
    }

    /** A collection or a map with no mapping annotation is not a column either: no SQL type holds it. */
    @Test
    void aCollectionOrAMapWithoutAnnotationIsNotAColumn() throws IOException {
        InProcessCompilation.Result result = InProcessCompilation.compileSources(out, "shop.Book", """
                package shop;

                import jakarta.persistence.Entity;
                import jakarta.persistence.Id;
                import java.util.Collection;
                import java.util.Map;

                @Entity
                public class Book {
                    @Id public Long id;
                    public String title;
                    public Collection<String> keywords;
                    public Map<String, String> notes;
                }
                """, BOOKS, BOOKS_SOURCE);

        assertThat(result.success()).as(result.errors().toString()).isTrue();
        assertThat(result.generatedSource("shop._Book")).contains(" title = ")
                .doesNotContain("keywords").doesNotContain("notes");
    }
}
