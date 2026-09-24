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
import io.vidocq.runtime.devservices.spi.DevServiceState;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Starts ephemeral PostgreSQL containers for a {@code vidocq:dev} session and exposes their JDBC
 * coordinates as {@code vidocq.pool[.<name>].url|username|password} — exactly the keys the Mansart pool
 * extension consumes, so the application wires onto them with zero app-side glue.
 *
 * <p><b>Multi-datasource.</b> Besides the {@code @Default} pool, every name listed in
 * {@code vidocq.dev.postgres.datasources} (e.g. {@code analytics,audit}) gets its own container, published
 * under {@code vidocq.pool.<name>.*}. The list is explicit because the dev service <i>creates</i> the URLs
 * — it cannot derive which names the application wants. Each datasource opts out individually: the
 * {@code @Default} is skipped when {@code vidocq.pool.url} is set, a named one when
 * {@code vidocq.pool.<name>.url} is set, so a developer pointing at their own database is never overridden.</p>
 *
 * <p><b>Tunables</b> (all optional), per datasource via {@code vidocq.dev.postgres.<name>.} and globally via
 * {@code vidocq.dev.postgres.}: {@code image} (global default applies to every datasource), {@code db},
 * {@code username}, {@code password}, {@code port} (fixed host port — random when unset, for a stable
 * external connection across restarts), plus the shared {@code vidocq.dev.reuse}.</p>
 */
public final class PostgresDevService implements DevService {

    private static final String DEFAULT_IMAGE    = "postgres:16-alpine";
    private static final String DEFAULT_DB       = "vidocq";
    private static final String DEFAULT_USERNAME = "vidocq";
    private static final String DEFAULT_PASSWORD = "vidocq";
    private static final String DEFAULT_NAME     = "default";

    private final List<PostgreSQLContainer<?>> containers = new ArrayList<>();
    private final List<String> images = new ArrayList<>();

    @Override
    public String id() {
        return "postgres";
    }

    @Override
    public int order() {
        return 100;
    }

    /** Applies when at least one requested datasource (the {@code @Default} or a named one) still needs a container. */
    @Override
    public boolean appliesWhen(DevServiceContext ctx) {
        return !plan(ctx).isEmpty();
    }

    @Override
    public Map<String, String> start(DevServiceContext ctx) {
        boolean reuse = reuse(ctx);
        Map<String, String> props = new LinkedHashMap<>();
        for (DatasourcePlan ds : plan(ctx)) {
            PostgreSQLContainer<?> c = new PostgreSQLContainer<>(
                    DockerImageName.parse(ds.image()).asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName(ds.db())
                    .withUsername(ds.username())
                    .withPassword(ds.password());
            if (ds.fixedPort() != null) {
                // Pin the host port so an external tool keeps the same coordinates across restarts.
                c.setPortBindings(List.of(ds.fixedPort() + ":5432"));
            }
            if (reuse) {
                c.withReuse(true);
            }
            c.start();
            containers.add(c);
            images.add(ds.image());
            props.put(ds.poolPrefix() + "url", c.getJdbcUrl());
            props.put(ds.poolPrefix() + "username", c.getUsername());
            props.put(ds.poolPrefix() + "password", c.getPassword());
            ctx.log().log(System.Logger.Level.INFO,
                    "Postgres dev service '" + ds.name() + "' ready at " + c.getJdbcUrl());
        }
        return props;
    }

    @Override
    public void stop() {
        for (PostgreSQLContainer<?> c : containers) {
            try {
                c.stop();
            } catch (RuntimeException ignored) {
                // best-effort teardown — keep stopping the remaining containers
            }
        }
        containers.clear();
    }

    @Override
    public DevServiceState describe(Map<String, String> injected) {
        return describe(injected, images.isEmpty() ? DEFAULT_IMAGE : images.getFirst());
    }

    /** The state for what {@link #start} returned: one endpoint per datasource, from its JDBC URL's host and port. */
    static DevServiceState describe(Map<String, String> injected, String image) {
        Map<String, String> endpoints = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : injected.entrySet()) {
            String key = e.getKey();
            if (!key.startsWith("vidocq.pool.") || !key.endsWith(".url")) {
                continue;
            }
            String name = key.equals("vidocq.pool.url")
                    ? DEFAULT_NAME
                    : key.substring("vidocq.pool.".length(), key.length() - ".url".length());
            URI uri = URI.create(e.getValue().substring("jdbc:".length()));
            endpoints.put(name, uri.getHost() + ":" + uri.getPort());
        }
        return new DevServiceState("postgres", image, endpoints, List.copyOf(injected.keySet()));
    }

    // ---- planning (pure; unit-testable without Docker) ----

    /** A datasource to provision: its name, the {@code vidocq.pool[.<name>].} key prefix, image and credentials. */
    record DatasourcePlan(String name, String poolPrefix, String image, String db,
                          String username, String password, Integer fixedPort) {
    }

    /**
     * The datasources this provider must create: the {@code @Default} (unless {@code vidocq.pool.url} is set)
     * plus every name in {@code vidocq.dev.postgres.datasources} whose {@code vidocq.pool.<name>.url} is not
     * already configured. Names are single-segment; blank and dotted entries are ignored.
     */
    static List<DatasourcePlan> plan(DevServiceContext ctx) {
        List<DatasourcePlan> out = new ArrayList<>();
        if (ctx.property("vidocq.pool.url").isEmpty()) {
            out.add(specFor(ctx, DEFAULT_NAME, "vidocq.pool.", "vidocq.dev.postgres."));
        }
        for (String name : parseNames(ctx.property("vidocq.dev.postgres.datasources").orElse(""))) {
            String poolPrefix = "vidocq.pool." + name + ".";
            if (ctx.property(poolPrefix + "url").isPresent()) {
                continue; // the application configured this named datasource itself
            }
            out.add(specFor(ctx, name, poolPrefix, "vidocq.dev.postgres." + name + "."));
        }
        return out;
    }

    private static DatasourcePlan specFor(DevServiceContext ctx, String name, String poolPrefix, String devPrefix) {
        String image = ctx.property(devPrefix + "image")
                .or(() -> ctx.property("vidocq.dev.postgres.image"))
                .orElse(DEFAULT_IMAGE);
        String db = ctx.property(devPrefix + "db")
                .orElse(DEFAULT_NAME.equals(name) ? DEFAULT_DB : name);
        String username = ctx.property(devPrefix + "username").orElse(DEFAULT_USERNAME);
        String password = ctx.property(devPrefix + "password").orElse(DEFAULT_PASSWORD);
        Integer fixedPort = parsePort(ctx, devPrefix + "port");
        return new DatasourcePlan(name, poolPrefix, image, db, username, password, fixedPort);
    }

    /**
     * Parses a fixed-port property, naming the offending key and value instead of a bare
     * {@code NumberFormatException}.
     */
    private static Integer parsePort(DevServiceContext ctx, String key) {
        return ctx.property(key)
                .map(text -> {
                    try {
                        return Integer.valueOf(text.trim());
                    } catch (NumberFormatException notANumber) {
                        throw new IllegalArgumentException(
                                key + " is not a number: '" + text + "'", notANumber);
                    }
                })
                .orElse(null);
    }

    private static List<String> parseNames(String csv) {
        Set<String> names = new LinkedHashSet<>();
        for (String raw : csv.split(",")) {
            String n = raw.trim();
            if (!n.isEmpty() && n.indexOf('.') < 0) {
                names.add(n);
            }
        }
        return new ArrayList<>(names);
    }

    private static boolean reuse(DevServiceContext ctx) {
        return ctx.property("vidocq.dev.reuse").map(Boolean::parseBoolean).orElse(false);
    }
}
