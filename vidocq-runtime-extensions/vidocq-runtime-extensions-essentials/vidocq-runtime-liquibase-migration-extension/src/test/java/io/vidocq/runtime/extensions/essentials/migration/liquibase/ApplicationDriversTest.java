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
package io.vidocq.runtime.extensions.essentials.migration.liquibase;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplicationDriversTest {

    @Test
    void connectsThroughADriverTheLoaderSees() throws Exception {
        try (var connection = ApplicationDrivers.connect("jdbc:h2:mem:drivers", "sa", "",
                ApplicationDriversTest.class.getClassLoader())) {
            assertFalse(connection.isClosed());
        }
    }

    @Test
    void noDriverNamesTheUrlAndTheFix() {
        var e = assertThrows(SQLException.class, () -> ApplicationDrivers.connect("jdbc:nothing:here", "u", "p",
                ApplicationDriversTest.class.getClassLoader()));
        assertTrue(e.getMessage().contains("jdbc:nothing:here"), e.getMessage());
        assertTrue(e.getMessage().contains("add the JDBC driver as a dependency"), e.getMessage());
    }
}
