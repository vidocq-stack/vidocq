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

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.Startup;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceUnit;
import jakarta.persistence.metamodel.EntityType;

/**
 * Prints, once the container started, the entities each unit manages and what one transaction wrote through both:
 * the line UnitsPerModuleIT reads.
 */
@ApplicationScoped
public class UnitsReport {

    @PersistenceUnit(unitName = "ledger")
    EntityManagerFactory ledger;

    @PersistenceUnit(unitName = "archive")
    EntityManagerFactory archive;

    @Inject
    Recorder recorder;

    void report(@Observes Startup startup) {
        recorder.record("first");
        System.out.println("UNITS ledger=" + entities(ledger) + " archive=" + entities(archive)
                + " entries=" + recorder.entries() + " notes=" + recorder.notes());
    }

    private static String entities(EntityManagerFactory factory) {
        return factory.getMetamodel().getEntities().stream()
                .map(type -> type.getJavaType().getName())
                .sorted()
                .toList()
                .toString();
    }
}
