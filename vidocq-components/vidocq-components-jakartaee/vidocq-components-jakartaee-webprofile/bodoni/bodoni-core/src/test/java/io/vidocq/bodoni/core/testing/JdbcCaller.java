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
import java.sql.SQLException;
import java.util.Properties;
import java.util.ServiceLoader;

/**
 * JDBC calls made from a chosen class loader: the probe loads this class into an isolated
 * loader (a server lib) and invokes it reflectively, so {@link DriverManager} sees that
 * loader as its caller's. References only {@code java.base} and {@code java.sql}.
 */
public final class JdbcCaller {

    private JdbcCaller() {
    }

    /** {@link DriverManager#getConnection(String, Properties)}, called from this class. */
    public static Connection viaDriverManager(String url, Properties properties) throws SQLException {
        return DriverManager.getConnection(url, properties);
    }

    /**
     * The first {@link Driver} that {@code ServiceLoader.load(Driver.class, loader)} provides
     * and that accepts {@code url}, or {@code null}.
     */
    public static Driver viaServiceLoader(ClassLoader loader, String url) {
        for (Driver driver : ServiceLoader.load(Driver.class, loader)) {
            if (accepts(driver, url)) {
                return driver;
            }
        }
        return null;
    }

    private static boolean accepts(Driver driver, String url) {
        try {
            return driver.acceptsURL(url);
        } catch (SQLException _) {
            return false;
        }
    }
}
