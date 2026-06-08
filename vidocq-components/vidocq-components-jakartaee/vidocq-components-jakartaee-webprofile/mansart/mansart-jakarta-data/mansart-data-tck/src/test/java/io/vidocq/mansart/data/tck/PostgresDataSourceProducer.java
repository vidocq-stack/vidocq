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
package io.vidocq.mansart.data.tck;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;

/**
 * M6.5 — Test-scope CDI producer of a PostgreSQL {@link DataSource} backed by Testcontainers.
 *
 * <p>Activated by {@link MansartTckArchiveAppender} when system property
 * {@code mansart.tck.dialect=pg} is set (typically via Maven profile {@code -Ptck-pg}).
 *
 * <p>The container is started once per JVM (static singleton) and shut down via {@link PreDestroy}
 * — Testcontainers' Ryuk reaper would also reap it, but explicit shutdown speeds up clean exit.
 *
 * <p>Each Mansart entity is mapped to its own table (created via {@code RepositoryRuntime} schema
 * generation), so a single PG database serves all 73 EntityTests; the container is reused across
 * deployments.
 *
 * <p>Requires Docker on the host. Pulled image: {@code postgres:17-alpine}.
 */
@Singleton
public class PostgresDataSourceProducer {

    private static final PostgreSQLContainer<?> CONTAINER;

    static {
        CONTAINER = new PostgreSQLContainer<>("postgres:17-alpine")
                .withDatabaseName("mansart_tck")
                .withUsername("mansart")
                .withPassword("mansart")
                .withReuse(false);
        CONTAINER.start();
        Runtime.getRuntime().addShutdownHook(new Thread(CONTAINER::stop, "pg-tck-stop"));
    }

    @Produces
    @Singleton
    public DataSource dataSource() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(CONTAINER.getJdbcUrl());
        ds.setUser(CONTAINER.getUsername());
        ds.setPassword(CONTAINER.getPassword());
        return ds;
    }

    @PreDestroy
    void stop() {
        // Container is shared across deployments; the JVM shutdown hook handles real cleanup.
    }
}
