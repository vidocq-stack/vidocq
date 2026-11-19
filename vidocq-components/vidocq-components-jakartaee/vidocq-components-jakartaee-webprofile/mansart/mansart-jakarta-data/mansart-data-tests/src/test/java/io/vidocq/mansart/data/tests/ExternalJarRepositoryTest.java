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

import io.vidocq.mansart.data.cdi.MansartDataExtension;
import io.vidocq.mansart.data.cdi.MansartRuntimeProducer;
import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.enterprise.inject.spi.DeploymentException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A {@code @Repository} interface of a dependency jar the build did not enrich (mansart#85): the jar is on the class
 * path but not in the bean archive, as under Vidocq, so nothing implements the interface. Instead of the container's
 * bare unsatisfied dependency, the deployment fails with the repository, the bean that injects it, the jar and the
 * fix.
 */
class ExternalJarRepositoryTest {

    private static final String BOOK = """
            package acme.lib;

            import jakarta.persistence.Entity;
            import jakarta.persistence.Id;

            @Entity
            public class Book {
                @Id public Long id;
                public String title;
            }
            """;
    private static final String BOOKS = """
            package acme.lib;

            import jakarta.data.repository.BasicRepository;
            import jakarta.data.repository.Repository;

            @Repository
            public interface Books extends BasicRepository<Book, Long> {
            }
            """;
    private static final String SHELF = """
            package acme.app;

            import acme.lib.Books;
            import jakarta.inject.Inject;
            import jakarta.inject.Singleton;

            @Singleton
            public class Shelf {
                @Inject
                Books books;
            }
            """;


    private static final String SHELVES = """
            package acme.lib;

            import jakarta.data.repository.DataRepository;
            import jakarta.data.repository.Repository;

            @Repository
            public interface Shelves extends DataRepository<Book, Long> {
            }
            """;
    private static final String SHELVES_IMPL = """
            package acme.app;

            import acme.lib.Shelves;
            import jakarta.inject.Singleton;

            @Singleton
            public class HandWrittenShelves implements Shelves {
            }
            """;
    private static final String LIBRARIAN = """
            package acme.app;

            import acme.lib.Shelves;
            import jakarta.inject.Inject;
            import jakarta.inject.Singleton;

            @Singleton
            public class Librarian {
                @Inject
                Shelves shelves;
            }
            """;

    @TempDir
    Path dir;

    private InProcessCompilation.Result library;
    private Path jar;

    @BeforeEach
    void compileTheLibrary() throws IOException {
        library = InProcessCompilation.compileLibrary(dir.resolve("lib"), true, "acme.lib.Book", BOOK,
                "acme.lib.Books", BOOKS, "acme.lib.Shelves", SHELVES);
        assertThat(library.errors()).isEmpty();
        jar = jar(library.classes(), dir.resolve("bookshelf-lib-1.0.0.jar"));
    }

    @Test
    void aRepositoryOfAJarNotEnrichedFailsTheDeploymentNamingTheJarAndTheFix() throws Exception {
        Path app = application(jar, "acme.app.Shelf", SHELF);
        try (URLClassLoader loader = loader(jar, app)) {
            assertThatThrownBy(() -> deploy(loader, loader.loadClass("acme.app.Shelf")))
                    .isInstanceOf(DeploymentException.class)
                    .hasMessageContaining("acme.lib.Books")
                    .hasMessageContaining("acme.app.Shelf")
                    .hasMessageContaining("the jar bookshelf-lib-1.0.0.jar")
                    .hasMessageContaining("vidocq:generate")
                    .hasMessageContaining("scanDependencies")
                    .hasMessageContaining("mansart-data-processor");
        }
    }

    @Test
    void aRepositoryOfAClassesDirectoryNamesTheDirectory() throws Exception {
        Path app = application(library.classes(), "acme.app.Shelf", SHELF);
        try (URLClassLoader loader = loader(library.classes(), app)) {
            assertThatThrownBy(() -> deploy(loader, loader.loadClass("acme.app.Shelf")))
                    .isInstanceOf(DeploymentException.class)
                    .hasMessageContaining("the directory classes")
                    .hasMessageContaining("vidocq:generate");
        }
    }

    /** A bean of the application that implements the repository satisfies it: nothing to report. */
    @Test
    void aRepositoryImplementedByABeanOfTheApplicationIsNotReported() throws Exception {
        Path app = application(jar, "acme.app.HandWrittenShelves", SHELVES_IMPL, "acme.app.Librarian", LIBRARIAN);
        try (URLClassLoader loader = loader(jar, app);
             VaubanContainer container = deploy(loader, loader.loadClass("acme.app.HandWrittenShelves"),
                     loader.loadClass("acme.app.Librarian"))) {
            assertThat(container.select(loader.loadClass("acme.app.Librarian"))).isNotNull();
        }
    }

    private static final String COUNTER = """
            package acme.app;

            import acme.lib.Books;
            import jakarta.inject.Inject;
            import jakarta.inject.Singleton;

            @Singleton
            public class Counter {
                final Books books;

                @Inject
                Counter(Books books) {
                    this.books = books;
                }
            }
            """;

    /** A constructor injection point has no field to name: the message names the bean alone. */
    @Test
    void aConstructorInjectionNamesTheBean() throws Exception {
        Path app = application(jar, "acme.app.Counter", COUNTER);
        try (URLClassLoader loader = loader(jar, app)) {
            assertThatThrownBy(() -> deploy(loader, loader.loadClass("acme.app.Counter")))
                    .isInstanceOf(DeploymentException.class)
                    .hasMessageContaining("injected in acme.app.Counter, has no implementation");
        }
    }

    /** The repository interface in the bean archive (a class-path application): the run-time path serves it. */
    @Test
    void aRepositoryOfTheArchiveIsServedAtRunTimeAndNotReported() throws Exception {
        Path app = application(jar, "acme.app.Shelf", SHELF);
        try (URLClassLoader loader = loader(jar, app);
             VaubanContainer container = deploy(loader, loader.loadClass("acme.app.Shelf"),
                     loader.loadClass("acme.lib.Book"), loader.loadClass("acme.lib.Books"))) {
            assertThat(container.select(loader.loadClass("acme.app.Shelf"))).isNotNull();
        }
    }

    /** Compiles the application's sources against {@code dependency}; returns its classes directory. */
    private Path application(Path dependency, String... fqnAndSourcePairs) throws IOException {
        InProcessCompilation.Result app = InProcessCompilation.compileSources(
                dir.resolve("app-" + Integer.toHexString(String.join("", fqnAndSourcePairs).hashCode())),
                List.of(), List.of(dependency), fqnAndSourcePairs);
        assertThat(app.errors()).isEmpty();
        return app.classes();
    }

    private URLClassLoader loader(Path... entries) throws IOException {
        URL[] urls = new URL[entries.length];
        for (int i = 0; i < entries.length; i++) {
            urls[i] = entries[i].toUri().toURL();
        }
        return new URLClassLoader(urls, getClass().getClassLoader());
    }

    /** Boots Vauban with Mansart's extension and {@code beans}, {@code context} as the context class loader. */
    private static VaubanContainer deploy(ClassLoader context, Class<?>... beans) {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(context);
        try {
            var builder = VaubanContainer.builder()
                    .addBeanClass(MansartDataExtension.class)
                    .addBeanClass(MansartRuntimeProducer.class)
                    .addBeanClass(RoutingDataSources.class);
            for (Class<?> bean : beans) {
                builder = builder.addBeanClass(bean);
            }
            return builder.build();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    /** Packs the class files under {@code classes} into {@code jar}, as the library's build would. */
    private static Path jar(Path classes, Path jar) throws IOException {
        try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(out);
             Stream<Path> files = Files.walk(classes)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                zip.putNextEntry(new ZipEntry(classes.relativize(file).toString().replace('\\', '/')));
                zip.write(Files.readAllBytes(file));
                zip.closeEntry();
            }
        }
        return jar;
    }
}
