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
package io.vidocq.runtime.extensions.jakartaee.web.noether.persistence;

import io.vidocq.runtime.extensions.jakartaee.web.noether.persistence.live.NoetherPersistenceLive;
import io.vidocq.runtime.extensions.jakartaee.web.noether.persistence.live.NoetherPersistenceLive.Unit;
import jakarta.persistence.Cache;
import jakarta.persistence.EntityGraph;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceUnitTransactionType;
import jakarta.persistence.PersistenceUnitUtil;
import jakarta.persistence.Query;
import jakarta.persistence.SchemaManager;
import jakarta.persistence.SynchronizationType;
import jakarta.persistence.TypedQueryReference;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.metamodel.Metamodel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** What the extension hands its {@code -dev} companion: the units of the boot, and the companion's name. */
class NoetherPersistenceLiveTest {

    private static final EntityManagerFactory FACTORY = new NamedFactory("notes");

    @AfterEach
    void clear() {
        NoetherPersistenceLive.clear();
    }

    @Test
    void theUnitsAreEmptyUntilPublishedThenACopyUntilCleared() {
        assertEquals(List.of(), NoetherPersistenceLive.units());
        Unit notes = new Unit("notes", true, "notes", "create", FACTORY);
        List<Unit> given = new ArrayList<>(List.of(notes));

        NoetherPersistenceLive.publish(given);
        given.clear();
        List<Unit> published = NoetherPersistenceLive.units();

        assertEquals(List.of(notes), published);
        assertThrows(UnsupportedOperationException.class, published::clear);
        NoetherPersistenceLive.clear();
        assertEquals(List.of(), NoetherPersistenceLive.units());
    }

    @Test
    void aUnitHasANameAndAFactory() {
        assertThrows(NullPointerException.class, () -> new Unit(null, false, null, "none", FACTORY));
        assertThrows(NullPointerException.class, () -> new Unit("notes", false, null, "none", null));
    }

    @Test
    void theJarNamesItsDevCompanionAndIsNoPanel() throws IOException {
        try (InputStream in = NoetherPersistenceExtension.class.getClassLoader()
                .getResourceAsStream("META-INF/vidocq/dev-module")) {
            assertNotNull(in, "META-INF/vidocq/dev-module");
            assertEquals("vidocq-runtime-noether-persistence-extension-dev",
                    new String(in.readAllBytes(), StandardCharsets.UTF_8).strip());
        }
        assertFalse(Arrays.stream(NoetherPersistenceExtension.class.getInterfaces())
                .anyMatch(type -> type.getName().startsWith("io.vidocq.runtime.spi.devconsole")));
    }

    /** A factory that only knows its name: the holder never calls it. */
    private record NamedFactory(String getName) implements EntityManagerFactory {

        @Override
        public EntityManager createEntityManager() {
            throw new UnsupportedOperationException();
        }

        @Override
        public EntityManager createEntityManager(Map<?, ?> map) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EntityManager createEntityManager(SynchronizationType synchronizationType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EntityManager createEntityManager(SynchronizationType synchronizationType, Map<?, ?> map) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CriteriaBuilder getCriteriaBuilder() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Metamodel getMetamodel() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public void close() {
            // nothing to release
        }

        @Override
        public PersistenceUnitTransactionType getTransactionType() {
            return PersistenceUnitTransactionType.JTA;
        }

        @Override
        public SchemaManager getSchemaManager() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Map<String, Object> getProperties() {
            return Map.of();
        }

        @Override
        public Cache getCache() {
            throw new UnsupportedOperationException();
        }

        @Override
        public PersistenceUnitUtil getPersistenceUnitUtil() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void addNamedQuery(String name, Query query) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T unwrap(Class<T> cls) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> void addNamedEntityGraph(String graphName, EntityGraph<T> entityGraph) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <R> Map<String, TypedQueryReference<R>> getNamedQueries(Class<R> resultType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <E> Map<String, EntityGraph<? extends E>> getNamedEntityGraphs(Class<E> entityType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void runInTransaction(Consumer<EntityManager> work) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <R> R callInTransaction(Function<EntityManager, R> work) {
            throw new UnsupportedOperationException();
        }
    }
}
