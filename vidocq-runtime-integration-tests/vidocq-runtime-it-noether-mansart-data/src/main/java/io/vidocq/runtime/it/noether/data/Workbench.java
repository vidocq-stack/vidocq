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
package io.vidocq.runtime.it.noether.data;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;

/**
 * Both sides in one {@code @Transactional} method: a container-managed persistence context of the unit
 * {@code workbench} and the repository {@link Items}, on the same default pool.
 */
@ApplicationScoped
public class Workbench {

    @PersistenceContext
    EntityManager entities;

    @Inject
    Items items;

    /** The unit writes and flushes; the repository reads, before the transaction commits. */
    @Transactional
    public boolean repositoryFindsWhatTheUnitFlushed(String id) {
        entities.persist(new Item(id, "unit"));
        entities.flush();
        return items.findById(id).isPresent();
    }

    /** The repository writes; the unit reads, before the transaction commits. */
    @Transactional
    public boolean unitFindsWhatTheRepositorySaved(String id) {
        items.save(new Item(id, "repository"));
        return entities.find(Item.class, id) != null;
    }

    /** Both write, then the method fails: the transaction rolls back. */
    @Transactional
    public void bothWriteThenFail(String unitId, String repositoryId) {
        entities.persist(new Item(unitId, "unit"));
        entities.flush();
        items.save(new Item(repositoryId, "repository"));
        throw new IllegalStateException("refused after both writes");
    }
}
