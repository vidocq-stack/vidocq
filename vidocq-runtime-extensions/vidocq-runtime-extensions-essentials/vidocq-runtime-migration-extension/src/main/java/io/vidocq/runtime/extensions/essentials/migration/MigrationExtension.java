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
package io.vidocq.runtime.extensions.essentials.migration;

import io.vidocq.runtime.spi.VidocqConfiguration;
import io.vidocq.runtime.spi.VidocqExtension;
import io.vidocq.vauban.core.container.VaubanContainerBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.TreeSet;

/**
 * Applies schema migrations at boot. Priority 150 — after the Chappe transport (100), before the
 * Mansart pool (200) — so the schema is ready before anything connects. Migrates from the
 * {@code vidocq.pool[.<name>].url|username|password} coordinates directly (Flyway/Liquibase manage
 * their own connection), so it needs no runtime {@link javax.sql.DataSource} and is decoupled from
 * mansart-pool. The {@code @Default} datasource is migrated whenever {@code vidocq.pool.url} is set;
 * a named datasource is migrated only when it sets {@code vidocq.migration.<name>.locations}.
 */
public final class MigrationExtension implements VidocqExtension {

    private static final System.Logger LOG = System.getLogger(MigrationExtension.class.getName());

    private static final String PREFIX = "vidocq.migration.";
    private static final String POOL_PREFIX = "vidocq.pool.";
    private static final String LOCATIONS_SUFFIX = ".locations";

    private SchemaMigrator migrator;
    private List<MigrationTarget> targets = List.of();

    @Override public String name() { return "migration"; }
    @Override public int priority() { return 150; }

    @Override
    public void configure(VidocqConfiguration cfg) {
        if (!cfg.property(PREFIX + "enabled").map(Boolean::parseBoolean).orElse(true)) {
            LOG.log(System.Logger.Level.DEBUG, "Migration disabled (vidocq.migration.enabled=false)");
            return;
        }
        this.targets = buildTargets(cfg);
        if (targets.isEmpty()) {
            return;
        }
        List<SchemaMigrator> found = new ArrayList<>();
        ServiceLoader.load(SchemaMigrator.class, MigrationExtension.class.getClassLoader())
                .forEach(found::add);
        this.migrator = select(found, cfg.property(PREFIX + "engine"));
        LOG.log(System.Logger.Level.INFO,
                "Migration configured: engine=" + migrator.engine()
                        + " datasources=" + targets.stream().map(MigrationTarget::dataSourceName).toList());
    }

    @Override
    public void beforeStart(VaubanContainerBuilder builder) {
        if (migrator == null || targets.isEmpty()) {
            return;
        }
        for (MigrationTarget t : targets) {
            MigrationResult r = migrator.migrate(t); // throws on failure → boot aborts (fail fast)
            LOG.log(System.Logger.Level.INFO,
                    "Migration done: datasource=" + t.dataSourceName()
                            + " applied=" + r.applied()
                            + " version=" + r.version());
        }
    }

    // ── package-private helpers (unit-tested) ─────────────────────────────────

    static List<MigrationTarget> buildTargets(VidocqConfiguration cfg) {
        List<MigrationTarget> result = new ArrayList<>();
        if (cfg.property(POOL_PREFIX + "url").isPresent()) {
            result.add(target(cfg, "default", POOL_PREFIX, PREFIX + "locations"));
        }
        for (String name : namedWithLocations(cfg)) {
            String poolPrefix = POOL_PREFIX + name + ".";
            if (cfg.property(poolPrefix + "url").isPresent()) {
                result.add(target(cfg, name, poolPrefix, PREFIX + name + LOCATIONS_SUFFIX));
            }
        }
        return result;
    }

    private static MigrationTarget target(VidocqConfiguration cfg, String name,
                                          String poolPrefix, String locKey) {
        return new MigrationTarget(name,
                cfg.property(poolPrefix + "url").orElseThrow(),
                cfg.property(poolPrefix + "username").orElse(null),
                cfg.property(poolPrefix + "password").orElse(null),
                cfg.property(locKey).map(s -> List.of(s.split("\\s*,\\s*"))).orElse(List.of()));
    }

    private static TreeSet<String> namedWithLocations(VidocqConfiguration cfg) {
        TreeSet<String> names = new TreeSet<>();
        for (String key : cfg.propertyNames()) {
            if (key.startsWith(PREFIX) && key.endsWith(LOCATIONS_SUFFIX)) {
                String name = key.substring(PREFIX.length(), key.length() - LOCATIONS_SUFFIX.length());
                if (!name.isEmpty() && name.indexOf('.') < 0) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    static SchemaMigrator select(List<SchemaMigrator> found, Optional<String> engine) {
        if (found.isEmpty()) {
            throw new IllegalStateException("vidocq.migration: migrations configured but no SchemaMigrator "
                    + "backend on the path — add the flyway or liquibase migration extension");
        }
        if (found.size() == 1) {
            return found.get(0);
        }
        return engine
                .flatMap(e -> found.stream().filter(m -> m.engine().equalsIgnoreCase(e)).findFirst())
                .orElseThrow(() -> new IllegalStateException("vidocq.migration: multiple backends present "
                        + found.stream().map(SchemaMigrator::engine).toList()
                        + " — set vidocq.migration.engine to one of them"));
    }
}
