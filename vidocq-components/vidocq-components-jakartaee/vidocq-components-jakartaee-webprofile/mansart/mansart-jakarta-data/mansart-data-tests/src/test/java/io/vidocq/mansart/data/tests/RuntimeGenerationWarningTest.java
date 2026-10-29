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

/**
 * A repository compiled without {@code mansart-data-processor} is implemented at run time. That fallback is kept but
 * exceptional, so it is announced by a framed warning at startup, once per repository; a repository the run-time
 * generator fails on, served by a {@code java.lang.reflect.Proxy}, gets a warning naming the cause.
 */
class RuntimeGenerationWarningTest {

    @Repository
    public interface WarnedAuthorRepo extends BasicRepository<Author, Long> {
        List<Author> findByName(String name);
    }

    @Repository
    public interface ProxiedAuthorRepo extends BasicRepository<Author, Long> {
        List<Author> findByName(String name);
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
     * interface is cut off from its nest host and from Mansart), the {@code Proxy} safety net serves the repository.
     */
    @Test
    void aRepositoryServedByTheProxySafetyNetNamesTheCause() throws Exception {
        Class<?> repoInterface = new CoreHidingLoader(getClass().getClassLoader())
                .loadClass(ProxiedAuthorRepo.class.getName());

        Object repo = data.runtimeRepository(repoInterface);

        assertThat(Proxy.isProxyClass(repo.getClass())).isTrue();
        assertThat(warnings).hasSize(1);
        assertThat(warnings.getFirst())
                .contains("MANSART — DYNAMIC PROXY FALLBACK")
                .contains(ProxiedAuthorRepo.class.getName())
                .contains("could not implement it (java.lang.");
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
            if (!name.equals(ProxiedAuthorRepo.class.getName())) {
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
}
