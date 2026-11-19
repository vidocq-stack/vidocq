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
package io.vidocq.bodoni.tck;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Run in a forked JVM by {@code TckDatabaseTest}, as a WAR host would connect: reads the fixture's rows
 * through the TCP server and prints {@code rows=<t1 count> isolation=<level> dob=<t2 row 1 dob>}.
 */
public final class RemoteProbe {
    private RemoteProbe() {
    }

    /** Arguments: the JDBC URL, the user, the password. */
    public static void main(String[] args) throws SQLException {
        try (Connection connection = DriverManager.getConnection(args[0], args[1], args[2]);
             Statement statement = connection.createStatement()) {
            int rows;
            try (ResultSet count = statement.executeQuery("SELECT COUNT(*) FROM t1")) {
                count.next();
                rows = count.getInt(1);
            }
            String dob;
            try (ResultSet row = statement.executeQuery("SELECT dob FROM t2 WHERE idNum = 1")) {
                row.next();
                dob = String.valueOf(row.getDate(1));
            }
            System.out.println("rows=" + rows + " isolation=" + connection.getTransactionIsolation() + " dob=" + dob);
        }
    }
}
