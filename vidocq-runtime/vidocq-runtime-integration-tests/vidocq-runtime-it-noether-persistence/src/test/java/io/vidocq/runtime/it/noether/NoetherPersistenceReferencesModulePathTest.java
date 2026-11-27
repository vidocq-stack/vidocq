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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.vidocq.runtime.core.VidocqBootstrap;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.transaction.UserTransaction;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The persistence references a class declares on itself, as a servlet written for a Jakarta EE server does, are
 * bound under {@code java:comp/env} when the application has the naming extension (Jakarta EE 11 Platform §5.13,
 * §5.14).
 */
@SuppressWarnings("BanJNDI") // the JNDI lookups are what this test checks, against Vidocq's own naming
class NoetherPersistenceReferencesModulePathTest {

    private static VidocqBootstrap bootstrap;

    @BeforeAll
    static void boot() throws Exception {
        bootstrap = VidocqBootstrap.create().configure().start();
    }

    @AfterAll
    static void shutdown() {
        if (bootstrap != null) {
            bootstrap.shutdown();
        }
    }

    private static Ledger ledger() {
        return CDI.current().select(Ledger.class).get();
    }

    @Test
    void a_context_reference_is_a_transaction_scoped_entity_manager_of_its_unit() throws Exception {
        var manager = (EntityManager) new InitialContext().lookup("java:comp/env/persistence/ledger");
        var transaction = CDI.current().select(UserTransaction.class).get();

        transaction.begin();
        manager.persist(new Entry("looked-up"));
        transaction.commit();

        assertEquals(1, ledger().count("looked-up"));
        assertSame(manager, new InitialContext().lookup("java:comp/env/persistence/ledger"));
    }

    @Test
    void a_unit_reference_is_the_factory_of_its_unit() throws NamingException {
        var factory = (EntityManagerFactory) new InitialContext().lookup("java:comp/env/persistence/ledger-factory");

        assertSame(ledger().factory(), factory);
    }
}
