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

import javax.sql.XAConnection;
import javax.sql.XADataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MANSART-007 phase 2 — a pool configured with {@code xaDataSourceClassName} exposes the
 * underlying driver's {@link XADataSource} through {@code unwrap}, so the JTA bridge takes the
 * real-XA enlistment path THROUGH the pool bean. Pooled (outside-TX) connections are untouched.
 */
class XaDataSourceExposureTest {

    private static final String URL = "jdbc:h2:mem:mansart-pool-xa;DB_CLOSE_DELAY=-1";

    @Test
    void xaConfiguredPoolUnwrapsToTheDriverXaDataSource() throws Exception {
        PoolConfig config = PoolConfig.builder()
                .jdbcUrl(URL)
                .username("sa")
                .xaDataSourceClassName("org.h2.jdbcx.JdbcDataSource")
                .build();
        try (MansartDataSource pool = MansartDataSource.of(config)) {
            assertThat(pool.isWrapperFor(XADataSource.class))
                    .as("an xa-configured pool must advertise the XADataSource capability")
                    .isTrue();

            XADataSource xa = pool.unwrap(XADataSource.class);
            XAConnection xaConnection = xa.getXAConnection();
            try (Connection c = xaConnection.getConnection();
                 Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT 1")) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(1);
            } finally {
                xaConnection.close();
            }

            assertThat(pool.unwrap(XADataSource.class))
                    .as("the XADataSource is built once and cached")
                    .isSameAs(xa);

            // Pooled path stays functional next to the XA exposure.
            try (Connection pooled = pool.getConnection();
                 Statement s = pooled.createStatement();
                 ResultSet rs = s.executeQuery("SELECT 2")) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(2);
            }
        }
    }

    @Test
    void plainPoolDoesNotAdvertiseXa() throws Exception {
        PoolConfig config = PoolConfig.builder()
                .jdbcUrl(URL)
                .username("sa")
                .build();
        try (MansartDataSource pool = MansartDataSource.of(config)) {
            assertThat(pool.isWrapperFor(XADataSource.class)).isFalse();
            assertThatThrownBy(() -> pool.unwrap(XADataSource.class))
                    .isInstanceOf(java.sql.SQLException.class);
        }
    }

    @Test
    void unknownXaClassFailsLoudlyAtUnwrap() {
        PoolConfig config = PoolConfig.builder()
                .jdbcUrl(URL)
                .username("sa")
                .xaDataSourceClassName("com.example.DoesNotExist")
                .build();
        try (MansartDataSource pool = MansartDataSource.of(config)) {
            assertThatThrownBy(() -> pool.unwrap(XADataSource.class))
                    .isInstanceOf(java.sql.SQLException.class)
                    .hasMessageContaining("com.example.DoesNotExist");
        }
    }
}
