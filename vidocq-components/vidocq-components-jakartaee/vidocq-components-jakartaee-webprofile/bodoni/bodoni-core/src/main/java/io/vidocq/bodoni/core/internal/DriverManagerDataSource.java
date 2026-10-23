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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
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
 * <p>No message, cause or {@code toString()} built here carries the password: the URL is
 * shown {@linkplain JdbcParameters#redactedUrl() redacted}; when no driver accepts the URL,
 * or a driver fails, the exception has no cause, and a driver's message is kept scrubbed of
 * the full URL and of every known secret.
 */
public final class DriverManagerDataSource implements DataSource {

    private static final String NO_CONNECTION_STATE = "08001";
    private static final String USER = "user";
    private static final String PASSWORD = "password";
    private static final String DRIVER_CLASS = "JDBC driver class '";
    private static final String MASK = "***";

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
                throw new SQLException(DRIVER_CLASS + driver + "' not found", NO_CONNECTION_STATE, e);
            } catch (LinkageError e) {
                // ExceptionInInitializerError, NoClassDefFoundError...: a class-loading error carries no URL
                throw new SQLException(DRIVER_CLASS + driver + "' cannot be loaded", NO_CONNECTION_STATE, e);
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

    /**
     * The driver's connection, {@code null} when it does not accept the URL. A driver failure
     * becomes Bodoni's own exception, the driver's SQLState and vendor code kept, its message
     * scrubbed, and no cause: a driver exception may repeat the URL or the password anywhere
     * in its chain. A {@code RuntimeException} of the driver is treated the same way, with
     * SQLState {@code 08001} and vendor code {@code 0}.
     */
    private Connection open(Driver driver, Properties properties) throws SQLException {
        String url = parameters.url();
        try {
            return driver.acceptsURL(url) ? driver.connect(url, properties) : null;
        } catch (SQLException e) {
            throw failure(driver, e.getMessage(), properties, e.getSQLState(), e.getErrorCode());
        } catch (RuntimeException e) {
            throw failure(driver, e.getMessage(), properties, NO_CONNECTION_STATE, 0);
        }
    }

    /** Bodoni's own exception for a driver failure: scrubbed message, no cause. */
    private SQLException failure(Driver driver, String message, Properties properties, String state, int code) {
        String reason = message == null ? "" : ": " + scrub(message, properties.getProperty(PASSWORD));
        return new SQLException("JDBC driver " + driver.getClass().getName() + " cannot connect to '"
                + parameters.redactedUrl() + "'" + reason, state, code);
    }

    /**
     * {@code message} with the full URL replaced by the redacted one and every known secret
     * (those of the parameter string, and {@code password}) by {@code ***}, longest first.
     */
    private String scrub(String message, String password) {
        String scrubbed = message;
        if (!parameters.url().isEmpty()) {
            scrubbed = scrubbed.replace(parameters.url(), parameters.redactedUrl());
        }
        // A driver may repeat the URL without its properties, cut inside a password holding ';'.
        String beforeProperties = parameters.beforeProperties();
        if (!beforeProperties.isEmpty()) {
            scrubbed = scrubbed.replace(beforeProperties, parameters.redactedUrl());
        }
        List<String> secrets = new ArrayList<>(parameters.secrets());
        if (password != null && !password.isEmpty()) {
            secrets.add(password);
        }
        secrets.sort(Comparator.comparingInt(String::length).reversed());
        for (String secret : secrets) {
            scrubbed = scrubbed.replace(secret, MASK);
        }
        return scrubbed;
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
