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
package acme.app;

import acme.ledger.Entry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;

/**
 * Writes through both units in one transaction: the library's by its name, the application's by its qualifier. The
 * qualified entity manager is {@code @TransactionScoped}, the default scope of a unit's entity manager bean: it is
 * reached within a transaction only, so the counts run in one too.
 */
@ApplicationScoped
public class Recorder {

    @PersistenceContext(unitName = "ledger")
    EntityManager entries;

    @Inject
    @Archived
    EntityManager notes;

    @Transactional
    public void record(String id) {
        entries.persist(new Entry(id));
        notes.persist(new Note(id));
    }

    @Transactional
    public long entries() {
        return entries.createQuery("select count(e) from Entry e", Long.class).getSingleResult();
    }

    @Transactional
    public long notes() {
        return notes.createQuery("select count(n) from Note n", Long.class).getSingleResult();
    }
}
