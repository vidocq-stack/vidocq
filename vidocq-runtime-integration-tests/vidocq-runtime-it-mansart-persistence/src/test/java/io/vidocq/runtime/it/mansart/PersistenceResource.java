/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.runtime.it.mansart;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceUnit;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.SynchronizationType;
import jakarta.transaction.TransactionManager;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/persistence")
@Produces(MediaType.TEXT_PLAIN)
public class PersistenceResource {
    static volatile EntityManagerFactory lastInjectedFactory;

    @PersistenceContext
    private EntityManager entityManager;
    private EntityManager unsynchronizedEntityManager;
    private EntityManagerFactory factory;

    @PersistenceContext(synchronization = SynchronizationType.UNSYNCHRONIZED)
    public void setUnsynchronizedEntityManager(EntityManager entityManager) {
        this.unsynchronizedEntityManager = entityManager;
    }

    @PersistenceUnit
    public void setFactory(EntityManagerFactory factory) {
        this.factory = factory;
        lastInjectedFactory = factory;
    }

    @Inject
    TransactionManager transactionManager;

    @GET
    @Path("/commit/{id}")
    public long commit(@PathParam("id") String id) throws Exception {
        transactionManager.begin();
        entityManager.persist(new PersistenceRecord(id));
        transactionManager.commit();
        return count(id);
    }

    @GET
    @Path("/rollback/{id}")
    public long rollback(@PathParam("id") String id) throws Exception {
        transactionManager.begin();
        entityManager.persist(new PersistenceRecord(id));
        transactionManager.rollback();
        return count(id);
    }

    @GET
    @Path("/factory")
    public String factory() {
        try {
            factory.close();
            return "closed";
        } catch (IllegalStateException expected) {
            return factory.isOpen() && TestDataSource.acquisitions() > 0 && !TestDataSource.closed()
                    ? "managed" : "ownership-error";
        }
    }

    @GET
    @Path("/identity/{id}")
    public String persistenceContextIdentity(@PathParam("id") String id) throws Exception {
        transactionManager.begin();
        try {
            PersistenceRecord record = new PersistenceRecord(id);
            entityManager.persist(record);
            return entityManager.find(PersistenceRecord.class, id) == record ? "same" : "different";
        } finally {
            transactionManager.commit();
        }
    }

    @GET
    @Path("/unsynchronized/{id}")
    public long unsynchronizedPersistenceContext(@PathParam("id") String id) throws Exception {
        transactionManager.begin();
        unsynchronizedEntityManager.persist(new PersistenceRecord(id));
        if (unsynchronizedEntityManager.isJoinedToTransaction()) {
            throw new IllegalStateException("Unsynchronized persistence context joined implicitly");
        }
        transactionManager.commit();
        unsynchronizedEntityManager.clear();
        return count(id);
    }

    @GET
    @Path("/unsynchronized-join/{id}")
    public long explicitlyJoinedUnsynchronizedPersistenceContext(@PathParam("id") String id) throws Exception {
        transactionManager.begin();
        unsynchronizedEntityManager.persist(new PersistenceRecord(id));
        unsynchronizedEntityManager.joinTransaction();
        transactionManager.commit();
        return count(id);
    }

    private long count(String id) throws Exception {
        transactionManager.begin();
        try {
            return entityManager.createQuery(
                    "select count(r) from PersistenceRecord r where r.id = :id", Long.class)
                    .setParameter("id", id)
                    .getSingleResult();
        } finally {
            transactionManager.commit();
        }
    }
}
