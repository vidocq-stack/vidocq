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
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceUnit;
import jakarta.transaction.Transactional;

/**
 * The unit {@code journal}, which no {@code persistence.xml} declares: the {@code vidocq.persistence.journal.*} keys
 * of {@code vidocq.properties} do, a JTA unit on the default pool.
 */
@ApplicationScoped
public class Journal {

    @PersistenceContext(unitName = "journal")
    EntityManager memos;

    @PersistenceUnit(unitName = "journal")
    EntityManagerFactory factory;

    @Transactional
    public void record(String id) {
        memos.persist(new Memo(id));
    }

    public long count(String id) {
        return memos.createQuery("select count(m) from Memo m where m.id = :id", Long.class)
                .setParameter("id", id)
                .getSingleResult();
    }

    public EntityManagerFactory factory() {
        return factory;
    }
}
