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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vidocq.runtime.core.VidocqBootstrap;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The runtime that created the factory closes it when it stops. */
class NoetherPersistenceShutdownModulePathTest {

    private VidocqBootstrap bootstrap;

    @AfterEach
    void shutdown() {
        if (bootstrap != null) {
            bootstrap.shutdown();
        }
    }

    @Test
    void the_stopped_runtime_closes_the_factory() throws Exception {
        bootstrap = VidocqBootstrap.create().configure().start();
        EntityManagerFactory factory = CDI.current().select(Ledger.class).get().factory();
        assertTrue(factory.isOpen());

        bootstrap.shutdown();
        bootstrap = null;

        assertFalse(factory.isOpen(), "the factory outlived the runtime");
    }
}
