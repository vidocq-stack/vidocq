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
package io.vidocq.runtime.extensions.essentials.migration.liquibase;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * Connects through the JDBC drivers the application's class loader sees, as a servlet container does:
 * {@code DriverManager} refuses a driver its caller's loader cannot see, and a driver of the application layer is
 * not visible from this extension (spec 2026-10-13-application-layer-dependencies §5).
 */
final class ApplicationDrivers {

    private ApplicationDrivers() {}

    static Connection connect(String url, String user, String password, ClassLoader loader) throws SQLException {
        Properties info = new Properties();
        if (user != null) {
            info.setProperty("user", user);
        }
        if (password != null) {
            info.setProperty("password", password);
        }
        // A broken provider (missing class, failing constructor) must not hide the next one, as in DriverManager.
        List<ServiceConfigurationError> broken = new ArrayList<>();
        Iterator<Driver> drivers = ServiceLoader.load(Driver.class, loader).iterator();
        for (Driver driver = next(drivers, broken); driver != null; driver = next(drivers, broken)) {
            if (driver.acceptsURL(url)) {
                Connection connection = driver.connect(url, info);
                if (connection != null) {
                    return connection;
                }
            }
        }
        SQLException none = new SQLException("No JDBC driver accepts " + url + " on the application's class path:"
                + " add the JDBC driver as a dependency of the application");
        broken.forEach(none::addSuppressed);
        throw none;
    }

    /** The next driver that loads, a broken provider skipped and added to {@code broken}; {@code null} at the end. */
    private static Driver next(Iterator<Driver> drivers, List<ServiceConfigurationError> broken) {
        while (true) {
            try {
                return drivers.hasNext() ? drivers.next() : null;
            } catch (ServiceConfigurationError e) {
                broken.add(e);
            }
        }
    }
}
