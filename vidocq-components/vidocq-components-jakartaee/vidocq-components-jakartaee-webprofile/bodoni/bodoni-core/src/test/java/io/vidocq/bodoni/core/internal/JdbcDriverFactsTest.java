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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.UUID;

import io.vidocq.bodoni.core.testing.JdbcCaller;
import org.junit.jupiter.api.Test;

/**
 * JDK probe behind the driver-discovery ruling of B4 (Tags 3.0 §10.1.1, §10.2): records how
 * {@link DriverManager} and {@link ServiceLoader} find a JDBC driver when Bodoni sits in a
 * class loader above the application's (a WAR on a server whose lib holds Bodoni). The
 * split-loader layout is {@code platform <- bodoni (JdbcCaller) <- app (H2)}. Kept as a
 * regression test: a JDK or H2 that changes one of these facts fails here first.
 */
class JdbcDriverFactsTest {

    private static final String H2_DRIVER = "org.h2.Driver";
    private static final String FOREIGN_URL = "jdbc:bodoni-none:x";

    private final String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";

    private static Properties saCredentials() {
        Properties properties = new Properties();
        properties.setProperty("user", "sa");
        properties.setProperty("password", "");
        return properties;
    }

    private static int selectFortyTwo(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT 42")) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }

    /** Invokes a static {@link JdbcCaller} method as loaded by {@code loader}, unwrapping its failure. */
    private static Object callAs(ClassLoader loader, String method, Class<?>[] types, Object... args)
            throws Exception {
        Method target = loader.loadClass(JdbcCaller.class.getName()).getMethod(method, types);
        try {
            return target.invoke(null, args);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
    }

    private static URLClassLoader bodoniLoader() {
        return isolated("bodoni", ClassLoader.getPlatformClassLoader(), location(JdbcCaller.class));
    }

    private static URLClassLoader appLoader(ClassLoader bodoni) throws ClassNotFoundException {
        return isolated("app", bodoni, location(Class.forName(H2_DRIVER)));
    }

    @Test
    void h2IsOnTheClassPathOfTheTestRun() throws ClassNotFoundException {
        assertFalse(Class.forName(H2_DRIVER).getModule().isNamed());
    }

    /**
     * Why {@code Loaders.isolated} is child first: below the platform class loader, a parent-first
     * loader never defines a class of a boot-layer module package, the platform loader hands it
     * to the application class loader.
     */
    @Test
    void platformLoaderHandsBootLayerModulePackagesToTheAppLoader() throws Exception {
        try (URLClassLoader parentFirst = new URLClassLoader("parent-first", new URL[] {location(JdbcCaller.class)},
                ClassLoader.getPlatformClassLoader());
                URLClassLoader bodoni = bodoniLoader()) {
            assertSame(JdbcCaller.class, parentFirst.loadClass(JdbcCaller.class.getName()));
            Class<?> copy = bodoni.loadClass(JdbcCaller.class.getName());
            assertSame(bodoni, copy.getClassLoader());
            assertFalse(copy.getModule().isNamed());
        }
    }

    @Test
    void driverManagerRefusesADriverItsCallerCannotSee() throws Exception {
        Properties credentials = saCredentials();
        try (URLClassLoader bodoni = bodoniLoader(); URLClassLoader app = appLoader(bodoni)) {
            SQLException refused = withContextLoader(app, () -> {
                Class.forName(H2_DRIVER, true, app);
                return assertThrows(SQLException.class, () -> callAs(bodoni, "viaDriverManager",
                        new Class<?>[] {String.class, Properties.class}, url, credentials));
            });
            assertEquals("08001", refused.getSQLState());
            assertTrue(refused.getMessage().contains(url), refused.getMessage());
        }
    }

    @Test
    void serviceLoaderOnTheContextLoaderFindsTheDriver() throws Exception {
        try (URLClassLoader bodoni = bodoniLoader(); URLClassLoader app = appLoader(bodoni)) {
            Driver driver = (Driver) callAs(bodoni, "viaServiceLoader",
                    new Class<?>[] {ClassLoader.class, String.class}, app, url);
            assertNotNull(driver);
            assertEquals(H2_DRIVER, driver.getClass().getName());
            assertEquals(app, driver.getClass().getClassLoader());
            try (Connection connection = driver.connect(url, saCredentials())) {
                assertEquals(42, selectFortyTwo(connection));
            }
        }
    }

    @Test
    void connectAnswersNullForAForeignUrl() throws Exception {
        try (URLClassLoader bodoni = bodoniLoader(); URLClassLoader app = appLoader(bodoni)) {
            Driver driver = (Driver) callAs(bodoni, "viaServiceLoader",
                    new Class<?>[] {ClassLoader.class, String.class}, app, url);
            assertNotNull(driver);
            assertFalse(driver.acceptsURL(FOREIGN_URL));
            assertNull(driver.connect(FOREIGN_URL, saCredentials()));
        }
    }

    @Test
    void driverManagerSeesADriverOfItsOwnLoader() throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, saCredentials())) {
            assertEquals(42, selectFortyTwo(connection));
        }
    }

    @Test
    void namedModuleServiceLoaderFindsTheDriver() {
        assertTrue(getClass().getModule().isNamed());
        List<String> found = new ArrayList<>();
        for (Driver driver : ServiceLoader.load(Driver.class, Thread.currentThread().getContextClassLoader())) {
            found.add(driver.getClass().getName());
        }
        assertTrue(found.contains(H2_DRIVER), found::toString);
    }
}
