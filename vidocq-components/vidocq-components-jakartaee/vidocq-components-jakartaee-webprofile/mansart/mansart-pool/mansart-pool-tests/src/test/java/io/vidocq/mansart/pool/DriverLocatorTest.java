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
package io.vidocq.mansart.pool;

import io.vidocq.mansart.pool.core.MansartDataSource;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Which JDBC driver a pool uses (mansart#42): a {@code java.sql.Driver} provider visible to the application's
 * class loader, else one registered with {@code DriverManager}.
 */
class DriverLocatorTest {

    @Test
    void theH2ProviderOnTheClassPathIsFound() throws SQLException {
        assertThat(MansartDataSource.driverClassFor("jdbc:h2:mem:locator")).isEqualTo("org.h2.Driver");
    }

    @Test
    void aNullContextLoaderFallsBackToThePoolsOwnLoader() throws SQLException {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(null);
        try {
            assertThat(MansartDataSource.driverClassFor("jdbc:h2:mem:locator")).isEqualTo("org.h2.Driver");
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    @Test
    void aUrlNoDriverAcceptsNamesTheUrlAndWhereItLooked() {
        assertThatThrownBy(() -> MansartDataSource.driverClassFor("jdbc:nosuchdb:x"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("No JDBC driver accepts jdbc:nosuchdb:x")
                .hasMessageContaining("java.sql.Driver providers visible to")
                .hasMessageContaining("DriverManager");
    }
}
