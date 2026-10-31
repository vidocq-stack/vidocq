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
package io.vidocq.runtime.extensions.jakartaee.web.mansart.pool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BUG-20261031-05 and -07: a pool without a database names both ways out, the URL and the dev service, and the test
 * listener a plain {@code mvn test} needs, instead of only asking whether the URL is set.
 */
class MissingDataSourceTest {

    @Test
    void theDefaultPoolNamesTheUrlTheDevServiceAndTheTestListener() {
        MansartPoolHolder.INSTANCE.set(null);
        MansartPoolHolder holder = new MansartPoolHolder();

        String message = assertThrows(IllegalStateException.class, holder::getConnection).getMessage();

        assertTrue(message.contains("set vidocq.pool.url"), message);
        assertTrue(message.contains("vidocq-runtime-devservice-postgres"), message);
        assertTrue(message.contains("vidocq extension add mansart-pool"), message);
        assertTrue(message.contains("vidocq-runtime-devservices-junit"), message);
    }

    @Test
    void aNamedPoolNamesItsOwnUrl() {
        String message = assertThrows(IllegalStateException.class,
                () -> NamedDataSourceRegistry.require("nowhere")).getMessage();

        assertTrue(message.contains("set vidocq.pool.nowhere.url"), message);
        assertTrue(message.contains("vidocq.dev.postgres.datasources"), message);
    }
}
