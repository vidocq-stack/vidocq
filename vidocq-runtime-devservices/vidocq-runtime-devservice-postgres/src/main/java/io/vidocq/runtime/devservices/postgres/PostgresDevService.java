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
package io.vidocq.runtime.devservices.postgres;

import io.vidocq.runtime.devservices.spi.DevService;
import io.vidocq.runtime.devservices.spi.DevServiceContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Starts an ephemeral PostgreSQL container for a {@code vidocq:dev} session and exposes its JDBC
 * coordinates as {@code vidocq.pool.url} / {@code vidocq.pool.username} / {@code vidocq.pool.password}
 * — exactly the keys the Mansart pool extension consumes, so the application wires onto it with zero
 * app-side glue.
 *
 * <p>Opts out (see {@link #appliesWhen}) when {@code vidocq.pool.url} is explicitly configured, so a
 * developer pointing at their own database is never overridden. Tunables (all optional):
 * {@code vidocq.dev.postgres.image|db|username|password} and the shared {@code vidocq.dev.reuse}.</p>
 */
public final class PostgresDevService implements DevService {

    private static final String DEFAULT_IMAGE = "postgres:16-alpine";

    private PostgreSQLContainer<?> container;

    @Override
    public String id() {
        return "postgres";
    }

    @Override
    public int order() {
        return 100;
    }

    @Override
    public boolean appliesWhen(DevServiceContext ctx) {
        return ctx.property("vidocq.pool.url").isEmpty();
    }

    @Override
    public Map<String, String> start(DevServiceContext ctx) {
        String image = ctx.property("vidocq.dev.postgres.image").orElse(DEFAULT_IMAGE);
        container = new PostgreSQLContainer<>(DockerImageName.parse(image)
                        .asCompatibleSubstituteFor("postgres"))
                .withDatabaseName(ctx.property("vidocq.dev.postgres.db").orElse("vidocq"))
                .withUsername(ctx.property("vidocq.dev.postgres.username").orElse("vidocq"))
                .withPassword(ctx.property("vidocq.dev.postgres.password").orElse("vidocq"));
        if (reuse(ctx)) {
            container.withReuse(true);
        }
        container.start();
        ctx.log().log(System.Logger.Level.INFO, "Postgres dev service ready at " + container.getJdbcUrl());

        Map<String, String> props = new LinkedHashMap<>();
        props.put("vidocq.pool.url", container.getJdbcUrl());
        props.put("vidocq.pool.username", container.getUsername());
        props.put("vidocq.pool.password", container.getPassword());
        return props;
    }

    @Override
    public void stop() {
        if (container != null) {
            container.stop();
            container = null;
        }
    }

    private static boolean reuse(DevServiceContext ctx) {
        return ctx.property("vidocq.dev.reuse").map(Boolean::parseBoolean).orElse(false);
    }
}
