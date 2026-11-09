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

import io.vidocq.mansart.data.processor.DependencyHints;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mansart's processor run over the compiled classes of a dependency jar (mansart#40, Vidocq/vidocq#246): the way
 * {@code vidocq:generate} implements the {@code @Repository} interfaces a library ships, with
 * {@code javac -proc:only} given their binary names. The library is compiled by a third party, with no annotation
 * processing, and often without {@code -parameters}.
 */
class ProcessorClassFileInputTest {

    private static final String BOOK = "lib.Book";
    private static final String BOOKS = "lib.Books";
    private static final String BOOK_SOURCE = """
            package lib;

            import jakarta.persistence.Entity;
            import jakarta.persistence.GeneratedValue;
            import jakarta.persistence.Id;

            @Entity
            public class Book {
                @Id @GeneratedValue public Long id;
                public String title;
                public String author;
            }
            """;

    /** The marker {@code vidocq:generate} reads to skip a jar Mansart already processed. */
    private static final String MARKER = "META-INF/io.vidocq.mansart/generated";

    @TempDir
    Path out;

    private static String repository(String methods) {
        return """
                package lib;

                import jakarta.data.repository.BasicRepository;
                import jakarta.data.repository.By;
                import jakarta.data.repository.Find;
                import jakarta.data.repository.Param;
                import jakarta.data.repository.Query;
                import jakarta.data.repository.Repository;
                import java.util.List;

                @Repository
                public interface Books extends BasicRepository<Book, Long> {
                %s
                }
                """.formatted(methods);
    }

    @Test
    void derivedQueriesAndNamedParametersNeedNoParameterNames() throws IOException {
        var library = InProcessCompilation.compileLibrary(out.resolve("lib"), false, BOOK, BOOK_SOURCE, BOOKS,
                repository("""
                    List<Book> findByTitle(String title);
                    @Query("where author = :author")
                    List<Book> byAuthor(@Param("author") String a);
                    @Find
                    List<Book> written(@By("author") String a);
                """));
        assertThat(library.success()).as(library.errors().toString()).isTrue();

        var result = InProcessCompilation.processClassFiles(library.classes(), out.resolve("gen"), BOOK, BOOKS);

        assertThat(result.errors()).isEmpty();
        assertThat(result.generatedSource("lib.BooksImpl")).contains("class BooksImpl");
        assertThat(Files.readString(result.classes().resolve("META-INF/mansart-repositories.list"),
                StandardCharsets.UTF_8)).contains("lib.Books=lib.BooksImpl");
        assertThat(result.classes().resolve(MARKER)).exists();
        // The values the Vidocq codegen bundle declares for vidocq:generate.
        assertThat(DependencyHints.CLASS_BYTE_HINTS).containsExactly("jakarta/data/repository/Repository");
        assertThat(result.classes().resolve(DependencyHints.INDEX)).exists();
    }

    /**
     * A {@code @Find} parameter with no name in the class file and no {@code @By} is an error, whatever its type: the
     * specification (§4.3) binds by {@code @By} or by name, and Mansart no longer guesses the entity's only attribute
     * of that type (mansart#85).
     */
    @Test
    void aFindParameterWithoutANameIsAnErrorNamingTheMethod() throws IOException {
        var library = InProcessCompilation.compileLibrary(out.resolve("lib"), false, BOOK, BOOK_SOURCE, BOOKS,
                repository("""
                    @Find
                    List<Book> publishedIn(Integer year);
                """));
        assertThat(library.success()).as(library.errors().toString()).isTrue();

        var result = InProcessCompilation.processClassFiles(library.classes(), out.resolve("gen"), BOOK, BOOKS);

        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().getFirst())
                .contains("lib.Books.publishedIn")
                .contains("its parameters have no names in the class file (the library was compiled without"
                        + " -parameters), so the attribute each parameter is compared to is unknown. Annotate the"
                        + " parameters with @By, or compile the library with -parameters.")
                .doesNotContain("..")
                .doesNotContain("of type");
    }

    /**
     * {@code vidocq:generate} hands the processor the classes whose bytes name {@code @Repository}: the repository,
     * not its entity. The processor generates the entity's metamodel itself, and the result compiles.
     */
    @Test
    void theRepositoryAloneIsEnoughTheEntityMetamodelIsGeneratedToo() throws IOException {
        var library = InProcessCompilation.compileLibrary(out.resolve("lib"), false, BOOK, BOOK_SOURCE, BOOKS,
                repository("""
                    List<Book> findByTitle(String title);
                """));
        assertThat(library.success()).as(library.errors().toString()).isTrue();

        var result = InProcessCompilation.processClassFiles(library.classes(), out.resolve("gen"), BOOKS);

        assertThat(result.errors()).isEmpty();
        assertThat(result.generatedSource("lib._Book")).contains("class _Book");
        var compiled = InProcessCompilation.compileGenerated(result, library.classes(), out.resolve("compiled"));
        assertThat(compiled.success()).as(compiled.errors().toString()).isTrue();
    }

    @Test
    void aFindWithoutParameterNamesIsAnErrorNamingTheMethodAndNothingIsGenerated() throws IOException {
        var library = InProcessCompilation.compileLibrary(out.resolve("lib"), false, BOOK, BOOK_SOURCE, BOOKS,
                repository("""
                    List<Book> findByTitle(String title);
                    @Find
                    List<Book> byAuthor(String author);
                """));
        assertThat(library.success()).as(library.errors().toString()).isTrue();

        var result = InProcessCompilation.processClassFiles(library.classes(), out.resolve("gen"), BOOK, BOOKS);

        assertThat(result.errors()).anySatisfy(error -> assertThat(error)
                .contains("lib.Books.byAuthor")
                .contains("@By")
                .contains("-parameters"));
        assertThat(result.generated().resolve("lib/BooksImpl.java")).doesNotExist();
        assertThat(result.classes().resolve(MARKER)).doesNotExist();
    }

    @Test
    void theSameFindIsImplementedWhenTheLibraryKeptItsParameterNames() throws IOException {
        var library = InProcessCompilation.compileLibrary(out.resolve("lib"), true, BOOK, BOOK_SOURCE, BOOKS,
                repository("""
                    @Find
                    List<Book> byAuthor(String author);
                """));
        assertThat(library.success()).as(library.errors().toString()).isTrue();

        var result = InProcessCompilation.processClassFiles(library.classes(), out.resolve("gen"), BOOK, BOOKS);

        assertThat(result.errors()).isEmpty();
        assertThat(result.generatedSource("lib.BooksImpl")).contains("byAuthor");
    }
}
