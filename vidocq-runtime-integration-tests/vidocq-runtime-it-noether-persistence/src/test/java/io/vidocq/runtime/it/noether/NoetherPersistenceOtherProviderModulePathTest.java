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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vidocq.runtime.core.VidocqBootstrap;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.persistence.spi.PersistenceUnitInfo;
import java.sql.SQLException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * A unit whose {@code <provider>} names another provider than Noether (Jakarta Persistence 3.2, §9.1, Noether
 * BUG-20261119-90): the runtime hands it to that provider's {@code createContainerEntityManagerFactory}, on the pool
 * its {@code jta-data-source} names, and its startup report lists Noether's units only (BUG-20261119-93).
 */
class NoetherPersistenceOtherProviderModulePathTest {

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

    @Test
    void the_provider_the_unit_names_creates_its_factory() {
        assertEquals("registry", CDI.current().select(Registrar.class).get().factory().getName());

        PersistenceUnitInfo unit = RecordingProvider.last();
        assertEquals("registry", unit.getPersistenceUnitName());
        assertEquals(RecordingProvider.class.getName(), unit.getPersistenceProviderClassName());
    }

    @Test
    void the_unit_runs_on_the_pool_its_jta_data_source_names() throws SQLException {
        CDI.current().select(Registrar.class).get().factory();

        try (var connection = RecordingProvider.last().getJtaDataSource().getConnection()) {
            assertTrue(connection.getMetaData().getURL().startsWith("jdbc:h2:mem:vidocq-it-noether-notes"),
                    connection.getMetaData().getURL());
        }
    }
}
