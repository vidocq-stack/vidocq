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
import jakarta.transaction.Transactional;

/**
 * The {@code archive} unit reached with {@code @Inject} (Jakarta EE 11 Platform §13.12): its {@code <qualifier>}
 * names {@link Archived}, so the transaction-scoped {@code EntityManager} and the {@code EntityManagerFactory} come
 * under that qualifier, with no unit name at the injection point.
 */
@ApplicationScoped
public class Archivist {

    @Inject
    @Archived
    EntityManager notes;

    @Inject
    @Archived
    EntityManagerFactory factory;

    @Inject
    Archive archive;

    @Transactional
    public void record(String id) {
        notes.persist(new Note(id));
    }

    /** Persists through the injected entity manager, then asks the {@code @PersistenceContext} one of the unit. */
    @Transactional
    public boolean sharesThePersistenceContextOfTheUnit(String id) {
        Note note = new Note(id);
        notes.persist(note);
        return archive.manages(note);
    }

    public EntityManagerFactory factory() {
        return factory;
    }
}
