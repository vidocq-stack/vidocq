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

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Iterator;
import java.util.Objects;
import java.util.Properties;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.logging.Logger;

import javax.sql.DataSource;

/**
 * The unpooled {@link DataSource} of a JDBC parameter string (Tags 3.0 §10.1.1, §10.2): every
 * {@code getConnection} opens a new connection. References nothing outside {@code java.base}
 * and {@code java.sql}.
 *
 * <p>Driver discovery. The class loader is captured at construction: the thread context class
 * loader, else this class's own. A named driver class is loaded there with
 * {@code Class.forName(driver, true, loader)} (spec §10.2, "ensure it is loaded"). A
 * connection comes from the first {@link Driver} of {@code ServiceLoader.load(Driver.class,
 * loader)} that accepts the URL and whose {@code connect} answers a connection, else from the
 * first such driver {@link DriverManager} hands this class (drivers registered by hand, or
 * visible to Bodoni's own class loader). {@code DriverManager} alone would refuse a driver
 * of a web application when Bodoni sits in the server's lib. A provider that fails to load
 * is skipped. A pre-JDBC 4 driver without {@code META-INF/services/java.sql.Driver}, in a
 * class loader Bodoni cannot see, is not supported.
 *
 * <p>No message or {@code toString()} built here carries the password: the URL is shown
 * {@linkplain JdbcParameters#redactedUrl() redacted}, and when no driver accepts the URL the
 * exception has no cause ({@code DriverManager}'s own message repeats the full URL).
 */
public final class DriverManagerDataSource implements DataSource {

    private static final String NO_CONNECTION_STATE = "08001";
    private static final String USER = "user";
    private static final String PASSWORD = "password";

    private final JdbcParameters parameters;
    private final ClassLoader loader;

    /**
     * Captures the class loader and loads the named driver class, if any.
     *
     * @throws SQLException with SQLState {@code 08001} when the named driver class is not found
     */
    public DriverManagerDataSource(JdbcParameters parameters) throws SQLException {
        this.parameters = Objects.requireNonNull(parameters, "parameters");
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        this.loader = context != null ? context : DriverManagerDataSource.class.getClassLoader();
        String driver = parameters.driver();
        if (driver != null) {
            try {
                Class.forName(driver, true, loader);
            } catch (ClassNotFoundException e) {
                throw new SQLException("JDBC driver class '" + driver + "' not found", NO_CONNECTION_STATE, e);
            }
        }
    }

    /** A new connection with the user and the password of the parameter string. */
    @Override
    public Connection getConnection() throws SQLException {
        return connect(parameters.user(), parameters.password());
    }

    /** A new connection with the given user and password. */
    @Override
    public Connection getConnection(String user, String password) throws SQLException {
        return connect(user, password);
    }

    private Connection connect(String user, String password) throws SQLException {
        Properties properties = new Properties();
        if (user != null) {
            properties.setProperty(USER, user);
        }
        if (password != null) {
            properties.setProperty(PASSWORD, password);
        }
        Connection connection = firstConnection(ServiceLoader.load(Driver.class, loader).iterator(), properties);
        if (connection == null) {
            connection = firstConnection(DriverManager.drivers().iterator(), properties);
        }
        if (connection == null) {
            throw new SQLException("no JDBC driver accepts '" + parameters.redactedUrl()
                    + "' (a string data source is a JNDI name under java:comp/env"
                    + " or url[,[driver][,[user][,password]]])", NO_CONNECTION_STATE);
        }
        return connection;
    }

    /** The connection of the first driver that accepts the URL and connects, else {@code null}. */
    private Connection firstConnection(Iterator<Driver> drivers, Properties properties) throws SQLException {
        while (true) {
            Driver driver;
            try {
                if (!drivers.hasNext()) {
                    return null;
                }
                driver = drivers.next();
            } catch (ServiceConfigurationError _) {
                continue;
            }
            Connection connection = open(driver, properties);
            if (connection != null) {
                return connection;
            }
        }
    }

    private Connection open(Driver driver, Properties properties) throws SQLException {
        String url = parameters.url();
        try {
            return driver.acceptsURL(url) ? driver.connect(url, properties) : null;
        } catch (SQLException e) {
            throw new SQLException("JDBC driver " + driver.getClass().getName() + " cannot connect to '"
                    + parameters.redactedUrl() + "'", e.getSQLState(), e.getErrorCode(), e);
        }
    }

    /** {@code null}: this data source does not log. */
    @Override
    public PrintWriter getLogWriter() {
        return null;
    }

    /** Ignored: this data source does not log. */
    @Override
    public void setLogWriter(PrintWriter out) {
        // ignored: nothing is logged
    }

    /** Ignored: the driver's own timeouts apply. */
    @Override
    public void setLoginTimeout(int seconds) {
        // ignored: the driver's own timeouts apply
    }

    /** {@code 0}: the driver's own timeouts apply. */
    @Override
    public int getLoginTimeout() {
        return 0;
    }

    /** Not supported: this data source does not log. */
    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException("DriverManagerDataSource does not log");
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        throw new SQLException("DriverManagerDataSource does not wrap " + iface.getName());
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this);
    }

    /** The redacted URL only. */
    @Override
    public String toString() {
        return "DriverManagerDataSource[" + parameters.redactedUrl() + "]";
    }
}
