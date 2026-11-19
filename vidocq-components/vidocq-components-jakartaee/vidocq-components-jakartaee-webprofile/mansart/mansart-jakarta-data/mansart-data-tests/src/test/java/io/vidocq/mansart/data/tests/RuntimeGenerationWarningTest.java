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
import jakarta.data.repository.BasicRepository;
import jakarta.data.repository.Repository;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A repository compiled without {@code mansart-data-processor} is implemented at run time. That fallback is kept but
 * exceptional, so it is announced by a framed warning at startup, once per repository; a repository the run-time
 * generator fails on throws an exception naming the cause (no {@code java.lang.reflect.Proxy} fallback).
 */
class RuntimeGenerationWarningTest {

    @Repository
    public interface WarnedAuthorRepo extends BasicRepository<Author, Long> {
        List<Author> findByName(String name);
    }

    @Repository
    public interface UnlinkableAuthorRepo extends BasicRepository<Author, Long> {
        List<Author> findByName(String name);
    }

    /** A repository the processor cannot implement: no top-level class of the package can see it. */
    private static final class Hidden {
        @Repository
        interface HiddenAuthorRepo extends BasicRepository<Author, Long> {
        }
    }

    private final Logger logger = Logger.getLogger("io.vidocq.mansart.data.core.RuntimeGeneration");
    private final List<String> warnings = new CopyOnWriteArrayList<>();
    private final Handler capture = new Handler() {
        @Override public void publish(LogRecord r) { warnings.add(r.getMessage()); }
        @Override public void flush() { }
        @Override public void close() { }
    };
    private MansartData data;

    @BeforeEach
    void setUp() {
        logger.addHandler(capture);
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:mansart-runtime-warning;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        data = MansartData.builder().dataSource(ds).build();
    }

    @AfterEach
    void tearDown() {
        logger.removeHandler(capture);
    }

    @Test
    void aRepositoryImplementedAtRunTimeIsAnnouncedOnce() {
        WarnedAuthorRepo repo = data.runtimeRepository(WarnedAuthorRepo.class);
        data.runtimeRepository(WarnedAuthorRepo.class);

        assertThat(Proxy.isProxyClass(repo.getClass())).isFalse();
        assertThat(warnings).hasSize(1);
        assertThat(warnings.getFirst())
                .contains("MANSART — RUNTIME CODE GENERATION FALLBACK")
                .contains(WarnedAuthorRepo.class.getName())
                .contains("mansart-data-processor");
    }

    /**
     * The generated class is defined next to the interface, in its loader; when it cannot be linked there (here the
     * interface is cut off from its nest host and from Mansart), there is no {@code Proxy} safety net: the creation
     * fails loudly and names the repository and the cause.
     */
    @Test
    void aRepositoryTheGeneratorCannotImplementFailsLoudly() throws Exception {
        Class<?> repoInterface = new CoreHidingLoader(getClass().getClassLoader())
                .loadClass(UnlinkableAuthorRepo.class.getName());

        assertThatThrownBy(() -> data.runtimeRepository(repoInterface))
                .isInstanceOf(MansartDataException.class)
                .hasMessageContaining(UnlinkableAuthorRepo.class.getName())
                .hasMessageContaining("no java.lang.reflect.Proxy fallback")
                .hasMessageContaining("mansart-data-processor")
                .hasCauseInstanceOf(LinkageError.class);
        assertThat(warnings).isEmpty();
    }

    /** Defines the repository interface itself, and hides the Mansart runtime from it. */
    private static final class CoreHidingLoader extends ClassLoader {

        CoreHidingLoader(ClassLoader parent) {
            super(parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("io.vidocq.mansart.data.core.")) {
                throw new ClassNotFoundException(name);
            }
            if (!name.equals(UnlinkableAuthorRepo.class.getName())) {
                return super.loadClass(name, resolve);
            }
            Class<?> loaded = findLoadedClass(name);
            if (loaded != null) {
                return loaded;
            }
            try (InputStream in = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                byte[] bytes = in.readAllBytes();
                return defineClass(name, bytes, 0, bytes.length);
            } catch (IOException e) {
                throw new ClassNotFoundException(name, e);
            }
        }
    }

    /**
     * MANSART-038: a repository served at run time although the processor ran says why — here, it is nested in a
     * private class, so no generated class of its package can implement it — instead of blaming a missing processor.
     */
    @Test
    void aRepositoryNotVisibleFromItsPackageIsAnnouncedWithThatCause() {
        data.runtimeRepository(Hidden.HiddenAuthorRepo.class);

        assertThat(warnings).hasSize(1);
        assertThat(warnings.getFirst())
                .contains(Hidden.HiddenAuthorRepo.class.getName())
                .contains("not visible from its package")
                .doesNotContain("compiled without the Mansart annotation processor");
    }
}
