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

package io.vidocq.bodoni.core.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.logging.Logger;

import javax.sql.DataSource;

import io.vidocq.bodoni.core.testing.TestNaming;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

/** Data-source resolution, Tags 3.0 §10.1.1 and §10.2. */
@Isolated
class DataSourcesTest {

    private static final String SECRET = "s3cret";

    private final String h2 = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1,org.h2.Driver,sa,";

    @AfterEach
    void tearDown() {
        TestNaming.uninstall();
    }

    @Test
    void anObjectIsReturnedAsIs() throws SQLException {
        DataSource ds = new LeakyDataSource();
        assertSame(ds, DataSources.resolve(ds));
    }

    @Test
    void aStringIsLookedUpUnderJavaCompEnv() throws SQLException {
        TestNaming.install();
        DataSource ds = new LeakyDataSource();
        TestNaming.bind("java:comp/env/jdbc/test", ds);
        assertSame(ds, DataSources.resolve("jdbc/test"));
        assertEquals(1, TestNaming.closedContexts());
    }

    @Test
    void aNameBoundToSomethingElseFails() {
        TestNaming.install();
        TestNaming.bind("java:comp/env/jdbc/str", "x");
        SQLException e = assertThrows(SQLException.class, () -> DataSources.resolve("jdbc/str"));
        assertEquals("JNDI name 'java:comp/env/jdbc/str' is bound to a java.lang.String,"
                + " not a javax.sql.DataSource", e.getMessage());
    }

    @Test
    void withoutNamingTheStringIsAJdbcParameterString() throws SQLException {
        assertSelect42(DataSources.resolve(h2));
    }

    @Test
    void withNamingAndNothingBoundTheStringIsAJdbcParameterString() throws SQLException {
        TestNaming.install();
        assertSelect42(DataSources.resolve(h2));
        assertEquals(1, TestNaming.closedContexts());
    }

    private static void assertSelect42(DataSource resolved) throws SQLException {
        assertInstanceOf(DriverManagerDataSource.class, resolved);
        try (Connection c = resolved.getConnection();
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT 42")) {
            assertTrue(rs.next());
            assertEquals(42, rs.getInt(1));
        }
    }

    @Test
    void aFailingContextCloseDoesNotLoseTheDataSource() throws SQLException {
        TestNaming.install();
        TestNaming.failOnClose();
        DataSource ds = new LeakyDataSource();
        TestNaming.bind("java:comp/env/jdbc/test", ds);
        assertSame(ds, DataSources.resolve("jdbc/test"));
        assertEquals(1, TestNaming.closedContexts());
    }

    @Test
    void aBlankStringFails() {
        SQLException e = assertThrows(SQLException.class, () -> DataSources.resolve(" "));
        assertEquals("the data source string is empty", e.getMessage());
    }

    @Test
    void anotherTypeFails() {
        SQLException e = assertThrows(SQLException.class, () -> DataSources.resolve(1));
        assertTrue(e.getMessage().contains("java.lang.Integer"));
    }

    @Test
    void nullFails() {
        SQLException e = assertThrows(SQLException.class, () -> DataSources.resolve(null));
        assertEquals("no data source", e.getMessage());
    }

    @Test
    void anUnknownDriverFailsWithoutTheSecret() {
        String source = "jdbc:h2:mem:x;PASSWORD=" + SECRET + ",org.nope.Driver,sa," + SECRET;
        SQLException e = assertThrows(SQLException.class, () -> DataSources.resolve(source));
        assertTrue(e.getMessage().contains("org.nope.Driver"));
        assertFalse(e.getMessage().contains(SECRET));
    }

    @Test
    void aBadBindingMessageHidesTheSecret() {
        TestNaming.install();
        String name = "jdbc:x:y;PASSWORD=" + SECRET + ",d,u," + SECRET;
        TestNaming.bind("java:comp/env/" + name, "x");
        SQLException e = assertThrows(SQLException.class, () -> DataSources.resolve(name));
        assertFalse(e.getMessage().contains(SECRET));
    }

    @Test
    void describeRedactsAString() {
        assertEquals("jdbc:x:y", DataSources.describe("jdbc:x:y;PASSWORD=" + SECRET + ",d,u," + SECRET));
    }

    @Test
    void describeNamesAThirdPartyDataSourceByClass() {
        DataSource ds = new LeakyDataSource();
        assertEquals(ds.getClass().getName(), DataSources.describe(ds));
    }

    @Test
    void describeUsesTheToStringOfOurOwnDataSource() throws SQLException {
        DataSource ds = DataSources.resolve(h2);
        assertEquals(ds.toString(), DataSources.describe(ds));
    }

    /** A DataSource whose toString() would leak a credential. */
    private static final class LeakyDataSource implements DataSource {
        @Override
        public Connection getConnection() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Connection getConnection(String user, String password) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
            // unused
        }

        @Override
        public void setLoginTimeout(int seconds) {
            // unused
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() {
            return null;
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            throw new SQLException("not a wrapper");
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }

        @Override
        public String toString() {
            return "password=" + SECRET;
        }
    }
}
