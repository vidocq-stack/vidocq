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
package io.vidocq.mansart.pool.core;

import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Iterator;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * Finds the JDBC driver of a pool (mansart#42). {@code DriverManager} hands out a driver only to a caller whose
 * class loader can load it. Under Vidocq the pool lives in the runtime layer while the driver lives with the
 * application, so the pool asks the application's class loader for {@code java.sql.Driver} providers itself, as
 * servlet containers do, and keeps {@code DriverManager} for drivers registered by hand.
 */
final class DriverLocator {

    private DriverLocator() {
    }

    /** The current thread's context class loader, or {@code anchor}'s loader when the thread has none. */
    static ClassLoader applicationLoader(Class<?> anchor) {
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        return context != null ? context : anchor.getClassLoader();
    }

    /**
     * The first {@code java.sql.Driver} provider visible to {@code loader} that accepts {@code jdbcUrl}, else the
     * driver {@code DriverManager} returns for it. A provider that fails to load or to answer is skipped.
     */
    static Driver find(String jdbcUrl, ClassLoader loader) throws SQLException {
        Iterator<Driver> providers = ServiceLoader.load(Driver.class, loader).iterator();
        while (true) {
            Driver driver;
            try {
                if (!providers.hasNext()) {
                    break;
                }
                driver = providers.next();
            } catch (ServiceConfigurationError broken) {
                continue; // a provider that cannot be loaded does not hide the next one
            }
            try {
                if (driver.acceptsURL(jdbcUrl)) {
                    return driver;
                }
            } catch (SQLException refused) {
                // this driver cannot even judge the URL: try the next one
            }
        }
        try {
            return DriverManager.getDriver(jdbcUrl);
        } catch (SQLException none) {
            throw new SQLException("No JDBC driver accepts " + jdbcUrl + ": searched the java.sql.Driver providers"
                    + " visible to " + loader + " and the drivers registered with DriverManager", "08001", none);
        }
    }
}
