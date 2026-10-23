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

import static io.vidocq.bodoni.core.testing.Loaders.isolated;
import static io.vidocq.bodoni.core.testing.Loaders.location;
import static io.vidocq.bodoni.core.testing.Loaders.withContextLoader;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URLClassLoader;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;

/** The data source of a JDBC parameter string, Tags 3.0 §10.1.1, §10.2 and §10.6. */
class DriverManagerDataSourceTest {

    private static final String H2_DRIVER = "org.h2.Driver";
    private static final String SECRET = "s3cret";
    private static final String NO_DRIVER_STATE = "08001";

    private final String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";

    private static int selectFortyTwo(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT 42")) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }

    private static DataSource dataSource(String parameters) throws SQLException {
        return new DriverManagerDataSource(JdbcParameters.parse(parameters));
    }

    @Test
    void getConnectionOpensANewConnectionEachTime() throws SQLException {
        DataSource dataSource = dataSource(url + ",,sa,");
        try (Connection first = dataSource.getConnection(); Connection second = dataSource.getConnection()) {
            assertNotSame(first, second);
            assertEquals(42, selectFortyTwo(first));
            assertEquals(42, selectFortyTwo(second));
        }
    }

    @Test
    void namedDriverIsLoaded() throws SQLException {
        DataSource dataSource = dataSource(url + "," + H2_DRIVER + ",sa,");
        try (Connection connection = dataSource.getConnection()) {
            assertEquals(42, selectFortyTwo(connection));
        }
    }

    @Test
    void getConnectionWithCredentialsUsesThem() throws SQLException {
        DataSource dataSource = dataSource(url + ",,nobody,nothing");
        try (Connection connection = dataSource.getConnection("sa", "")) {
            assertEquals(42, selectFortyTwo(connection));
        }
    }

    @Test
    void unknownDriverClassFailsAtConstruction() {
        JdbcParameters parameters = JdbcParameters.parse(url + ",org.nope.Driver");
        SQLException failure = assertThrows(SQLException.class, () -> new DriverManagerDataSource(parameters));
        assertEquals("JDBC driver class 'org.nope.Driver' not found", failure.getMessage());
        assertEquals(NO_DRIVER_STATE, failure.getSQLState());
        assertInstanceOf(ClassNotFoundException.class, failure.getCause());
    }

    @Test
    void noDriverForTheUrlFailsWithoutCauseNorPassword() throws SQLException {
        DataSource dataSource = dataSource("jdbc:bodoni-none:x;PASSWORD=" + SECRET);
        SQLException failure = assertThrows(SQLException.class, dataSource::getConnection);
        assertEquals(NO_DRIVER_STATE, failure.getSQLState());
        assertNull(failure.getCause());
        assertEquals("no JDBC driver accepts 'jdbc:bodoni-none:x' (a string data source is a JNDI name under"
                + " java:comp/env or url[,[driver][,[user][,password]]])", failure.getMessage());
        assertFalse(failure.getMessage().contains(SECRET));
    }

    @Test
    void driverFailureIsKeptAsCause() throws SQLException {
        DataSource dataSource = dataSource(url + ",,sa,");
        try (Connection open = dataSource.getConnection()) {
            assertEquals(42, selectFortyTwo(open));
            SQLException failure = assertThrows(SQLException.class, () -> dataSource.getConnection("sa", SECRET));
            SQLException cause = assertInstanceOf(SQLException.class, failure.getCause());
            assertTrue(cause.getClass().getName().startsWith("org.h2."), cause.getClass().getName());
            assertFalse(failure.getMessage().contains(SECRET), failure.getMessage());
        }
    }

    @Test
    void toStringShowsTheRedactedUrlOnly() throws SQLException {
        String text = dataSource("jdbc:h2:mem:x;PASSWORD=" + SECRET + ",,sa," + SECRET).toString();
        assertEquals("DriverManagerDataSource[jdbc:h2:mem:x]", text);
    }

    @Test
    void dataSourcePlumbing() throws SQLException {
        DataSource dataSource = dataSource(url);
        assertSame(dataSource, dataSource.unwrap(DataSource.class));
        assertTrue(dataSource.isWrapperFor(DataSource.class));
        assertFalse(dataSource.isWrapperFor(Connection.class));
        assertThrows(SQLException.class, () -> dataSource.unwrap(Connection.class));
        assertNull(dataSource.getLogWriter());
        dataSource.setLogWriter(null);
        dataSource.setLoginTimeout(5);
        assertEquals(0, dataSource.getLoginTimeout());
        assertThrows(SQLFeatureNotSupportedException.class, dataSource::getParentLogger);
    }

    /** A WAR whose driver only its own loader sees, with Bodoni in the server's lib. */
    @Test
    void aDriverOnlyTheContextLoaderSeesIsUsed() throws Exception {
        ClassLoader platform = ClassLoader.getPlatformClassLoader();
        try (URLClassLoader bodoni = isolated("bodoni", platform, location(DriverManagerDataSource.class));
                URLClassLoader app = isolated("app", bodoni, location(Class.forName(H2_DRIVER)))) {
            DataSource dataSource = withContextLoader(app,
                    () -> isolatedDataSource(bodoni, url + "," + H2_DRIVER + ",sa,"));
            assertSame(bodoni, dataSource.getClass().getClassLoader());
            int answer = withContextLoader(platform, () -> {
                try (Connection connection = dataSource.getConnection()) {
                    return selectFortyTwo(connection);
                }
            });
            assertEquals(42, answer);
        }
    }

    @Test
    void aDriverNeitherLoaderSeesIsNotFound() throws Exception {
        ClassLoader platform = ClassLoader.getPlatformClassLoader();
        try (URLClassLoader bodoni = isolated("bodoni", platform, location(DriverManagerDataSource.class));
                URLClassLoader app = isolated("app", bodoni, location(Class.forName(H2_DRIVER)))) {
            Class.forName(H2_DRIVER, true, app);
            DataSource dataSource = withContextLoader(platform, () -> isolatedDataSource(bodoni, url + ",,sa,"));
            SQLException failure = assertThrows(SQLException.class, dataSource::getConnection);
            assertEquals(NO_DRIVER_STATE, failure.getSQLState());
            assertNull(failure.getCause());
        }
    }

    /** {@code new DriverManagerDataSource(JdbcParameters.parse(parameters))}, as loaded by {@code loader}. */
    private static DataSource isolatedDataSource(ClassLoader loader, String parameters) throws Exception {
        Class<?> parametersType = loader.loadClass(JdbcParameters.class.getName());
        Object parsed = parametersType.getMethod("parse", String.class).invoke(null, parameters);
        return (DataSource) loader.loadClass(DriverManagerDataSource.class.getName())
                .getConstructor(parametersType)
                .newInstance(parsed);
    }
}
