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

package io.vidocq.bodoni.core.testing;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * A JDBC driver for {@code jdbc:bodoni-manual:} URLs that a test registers with
 * {@link DriverManager} by hand: it is not listed in {@code META-INF/services}, so only the
 * {@code DriverManager} fallback finds it. It either delegates to another URL or refuses
 * every connection with a message that repeats the password and the URL.
 */
public final class ManualDriver implements Driver {

    /** The SQLState of a refused connection. */
    public static final String REFUSED_STATE = "28000";
    /** The vendor code of a refused connection. */
    public static final int REFUSED_CODE = 4242;

    private static final String PREFIX = "jdbc:bodoni-manual:";

    private final String target;
    private final AtomicInteger connections = new AtomicInteger();

    private ManualDriver(String target) {
        this.target = target;
    }

    /** A driver that opens {@code target} through {@link DriverManager} for every accepted URL. */
    public static ManualDriver delegatingTo(String target) {
        return new ManualDriver(target);
    }

    /** A driver that refuses every accepted URL with {@code refused password <password> for <url>}. */
    public static ManualDriver echoingThePassword() {
        return new ManualDriver(null);
    }

    /** How many connections this driver opened. */
    public int connections() {
        return connections.get();
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) {
            return null;
        }
        if (target == null) {
            throw new SQLException("refused password " + info.getProperty("password") + " for " + url,
                    REFUSED_STATE, REFUSED_CODE);
        }
        Connection connection = DriverManager.getConnection(target, info);
        connections.incrementAndGet();
        return connection;
    }

    @Override
    public boolean acceptsURL(String url) {
        return url != null && url.startsWith(PREFIX);
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
        return new DriverPropertyInfo[0];
    }

    @Override
    public int getMajorVersion() {
        return 1;
    }

    @Override
    public int getMinorVersion() {
        return 0;
    }

    @Override
    public boolean jdbcCompliant() {
        return false;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException("ManualDriver does not log");
    }
}
