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

import io.vidocq.runtime.core.VidocqAppLayer;
import io.vidocq.runtime.extensions.essentials.migration.MigrationResult;
import io.vidocq.runtime.extensions.essentials.migration.MigrationTarget;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;

/**
 * The main of the child JVM of {@link LayeredLiquibaseProcessTest}: installs the application layer from
 * {@code -Dvidocq.app.path} as {@code Vidocq.main} does, migrates the H2 database named by {@code args[0]} from
 * the default changelog, and prints what it did.
 */
public final class LayeredLiquibaseProbe {

    private LayeredLiquibaseProbe() {}

    public static void main(String[] args) throws Exception {
        if (!VidocqAppLayer.installIfConfigured()) {
            System.out.println("NO LAYER");
            System.exit(2);
        }
        String url = args[0];
        MigrationResult r;
        try {
            r = new LiquibaseSchemaMigrator().migrate(new MigrationTarget("default", url, "sa", "", List.of()));
        } catch (RuntimeException e) {
            System.out.println("FAILED " + e.getMessage());
            return;
        }
        System.out.println("RESULT applied=" + r.applied() + " nothingFound=" + r.nothingFound());
        try (Connection c = DriverManager.getConnection(url, "sa", "");
             var s = c.createStatement();
             var rs = s.executeQuery("SELECT COUNT(*) FROM gadget")) {
            rs.next();
            System.out.println("ROWS " + rs.getInt(1));
        } catch (SQLException e) {
            System.out.println("ROWS none: " + e.getMessage());
        }
    }
}
