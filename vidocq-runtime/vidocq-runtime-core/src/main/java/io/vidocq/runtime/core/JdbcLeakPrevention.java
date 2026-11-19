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

package io.vidocq.runtime.core;

import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Collections;

/**
 * Never loaded by the core: {@link LayerJdbcDrivers} reads these bytes and defines them, renamed, in a package of a
 * closing application layer, because {@link DriverManager} only lists and deregisters the drivers its caller's
 * class loader sees. No reference to its own class: the rename touches nothing else.
 */
final class JdbcLeakPrevention {

    private JdbcLeakPrevention() {}

    /** Deregisters every driver defined by {@code owner}; returns how many. */
    public static int deregister(ClassLoader owner) throws SQLException {
        int count = 0;
        for (Driver driver : Collections.list(DriverManager.getDrivers())) {
            if (driver.getClass().getClassLoader() == owner) {
                DriverManager.deregisterDriver(driver);
                count++;
            }
        }
        return count;
    }
}
