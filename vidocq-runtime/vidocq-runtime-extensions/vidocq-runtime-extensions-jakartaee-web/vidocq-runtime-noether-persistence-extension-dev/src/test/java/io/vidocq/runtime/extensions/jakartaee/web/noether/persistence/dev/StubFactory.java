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
package io.vidocq.runtime.extensions.jakartaee.web.noether.persistence.dev;

import io.vidocq.noether.jpa.core.spi.SchemaScripts;
import io.vidocq.noether.jpa.core.spi.UnitDescription;
import jakarta.persistence.Cache;
import jakarta.persistence.EntityGraph;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.PersistenceUnitTransactionType;
import jakarta.persistence.PersistenceUnitUtil;
import jakarta.persistence.Query;
import jakarta.persistence.SchemaManager;
import jakarta.persistence.SynchronizationType;
import jakarta.persistence.TypedQueryReference;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.metamodel.Metamodel;

import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A factory of a unit for the panel's tests: it answers its name, its {@link UnitDescription} and its
 * {@link SchemaScripts} through {@code unwrap}, and opens the entity managers {@code entityManagers} gives.
 */
record StubFactory(String getName, UnitDescription description, SchemaScripts scripts,
                   Supplier<EntityManager> entityManagers) implements EntityManagerFactory {

    StubFactory(String name, UnitDescription description) {
        this(name, description, null, () -> {
            throw new UnsupportedOperationException("no entity manager in this test");
        });
    }

    @Override
    public EntityManager createEntityManager() {
        return entityManagers.get();
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
        if (cls == UnitDescription.class && description != null) {
            return cls.cast(description);
        }
        if (cls == SchemaScripts.class && scripts != null) {
            return cls.cast(scripts);
        }
        throw new PersistenceException("Unsupported unwrap type " + cls.getName());
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
