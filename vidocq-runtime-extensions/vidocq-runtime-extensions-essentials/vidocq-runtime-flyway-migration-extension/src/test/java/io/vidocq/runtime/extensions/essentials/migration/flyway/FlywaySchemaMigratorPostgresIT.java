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
package io.vidocq.runtime.extensions.essentials.migration.flyway;

import io.vidocq.runtime.extensions.essentials.migration.MigrationResult;
import io.vidocq.runtime.extensions.essentials.migration.MigrationTarget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Docker-gated integration test for {@link FlywaySchemaMigrator} against a real PostgreSQL instance.
 * Skips automatically when Docker is not usable.
 */
class FlywaySchemaMigratorPostgresIT {

    @Test
    @Timeout(240)
    void migratesPostgres() throws Exception {
        String unusable = dockerProblem();
        assumeTrue(unusable == null, () -> "Docker is not usable here, skipping: " + unusable);
        try (PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine")) {
            pg.start();
            MigrationResult r = new FlywaySchemaMigrator().migrate(new MigrationTarget(
                    "default", pg.getJdbcUrl(), pg.getUsername(), pg.getPassword(),
                    List.of("classpath:db/testmigration")));
            assertEquals(1, r.applied());
            try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
                 var s = c.createStatement();
                 var rs = s.executeQuery("SELECT COUNT(*) FROM widget")) {
                assertTrue(rs.next());
            }
        }
    }

    /**
     * Why Docker cannot be used here, or {@code null} when it can.
     *
     * <p>{@code isDockerAvailable()} reads as a boolean probe, but it starts Testcontainers' resource reaper on
     * the way. A daemon that answers while the reaper cannot run makes it throw instead of returning
     * {@code false}, and this test then fails the build rather than skipping — which is how a CI runner whose
     * Docker endpoint had just started working turned a gated test red. Whatever it throws means one thing for
     * this test: no usable Docker. The reason travels into the skip message, so a run that skips says why.
     */
    private static String dockerProblem() {
        try {
            return DockerClientFactory.instance().isDockerAvailable() ? null : "no Docker daemon answered";
        } catch (RuntimeException | LinkageError unusable) {
            return unusable.getClass().getSimpleName() + ": " + unusable.getMessage();
        }
    }
}
