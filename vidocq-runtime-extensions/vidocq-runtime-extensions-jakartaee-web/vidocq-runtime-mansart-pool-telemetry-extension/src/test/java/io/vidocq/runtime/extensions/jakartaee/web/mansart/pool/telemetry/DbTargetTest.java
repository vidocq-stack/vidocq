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
package io.vidocq.runtime.extensions.jakartaee.web.mansart.pool.telemetry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The database a span names, read from the JDBC URL as the OpenTelemetry semantic conventions spell it. */
class DbTargetTest {

    @Test
    void postgresqlGivesItsHostPortAndDatabase() {
        DbTarget target = DbTarget.of("jdbc:postgresql://db.example:5433/library?ssl=true");

        assertEquals("postgresql", target.system());
        assertEquals("library", target.namespace());
        assertEquals("db.example", target.host());
        assertEquals(5433, target.port());
    }

    @Test
    void anInMemoryH2DatabaseHasANameAndNoServer() {
        DbTarget target = DbTarget.of("jdbc:h2:mem:books;DB_CLOSE_DELAY=-1");

        assertEquals("h2", target.system());
        assertEquals("books", target.namespace());
        assertNull(target.host());
        assertEquals(-1, target.port());
    }

    @Test
    void systemsTakeTheirSemanticConventionNames() {
        assertEquals("microsoft.sql_server", DbTarget.of("jdbc:sqlserver://h:1433;databaseName=x").system());
        assertEquals("oracle.db", DbTarget.of("jdbc:oracle:thin:@//h:1521/svc").system());
        assertEquals("mariadb", DbTarget.of("jdbc:mariadb://h/x").system());
        assertEquals("other_sql", DbTarget.of(null).system());
    }
}
