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
package io.vidocq.runtime.it.noether;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceUnit;
import jakarta.persistence.SynchronizationType;
import jakarta.transaction.TransactionManager;
import jakarta.transaction.Transactional;

/**
 * What an application does with the unit: container-managed persistence contexts in container transactions. The
 * application has two units, so each injection point names its own.
 */
@ApplicationScoped
public class Ledger {

    @PersistenceContext(unitName = "ledger")
    EntityManager entries;

    @PersistenceContext(unitName = "ledger", synchronization = SynchronizationType.UNSYNCHRONIZED)
    EntityManager drafts;

    @PersistenceUnit(unitName = "ledger")
    EntityManagerFactory factory;

    @Inject
    TransactionManager transactions;

    @Transactional
    public void record(String id) {
        entries.persist(new Entry(id));
    }

    @Transactional
    public void recordThenFail(String id) {
        entries.persist(new Entry(id));
        throw new IllegalStateException("refused after persisting " + id);
    }

    @Transactional
    public boolean findsWhatItPersistedWithinOneTransaction(String id) {
        Entry entry = new Entry(id);
        entries.persist(entry);
        return entries.find(Entry.class, id) == entry;
    }

    /** Persists through the unsynchronized context without joining it: nothing is written. */
    public boolean draftWithoutJoining(String id) throws Exception {
        transactions.begin();
        boolean joined;
        try {
            drafts.persist(new Entry(id));
            joined = drafts.isJoinedToTransaction();
        } finally {
            transactions.commit();
        }
        drafts.clear();
        return joined;
    }

    /** Persists through the unsynchronized context, then joins it to the transaction: the entry is written. */
    public void draftThenJoin(String id) throws Exception {
        transactions.begin();
        try {
            drafts.persist(new Entry(id));
            drafts.joinTransaction();
        } finally {
            transactions.commit();
        }
    }

    public long count(String id) {
        return entries.createQuery("select count(e) from Entry e where e.id = :id", Long.class)
                .setParameter("id", id)
                .getSingleResult();
    }

    public EntityManagerFactory factory() {
        return factory;
    }
}
