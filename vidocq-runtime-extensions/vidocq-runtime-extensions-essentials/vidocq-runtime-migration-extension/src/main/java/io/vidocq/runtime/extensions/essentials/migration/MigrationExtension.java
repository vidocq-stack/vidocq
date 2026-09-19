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
import io.vidocq.runtime.spi.report.StartupReportContext;
import io.vidocq.runtime.spi.report.StartupReportContributor;
import io.vidocq.runtime.spi.report.StartupReportSection;
import io.vidocq.runtime.spi.report.Verbosity;
import io.vidocq.vauban.core.container.VaubanContainerBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Applies schema migrations at boot. Priority 150 — after the Chappe transport (100), before the
 * Mansart pool (200) — so the schema is ready before anything connects. Migrates from the
 * {@code vidocq.pool[.<name>].url|username|password} coordinates directly (Flyway/Liquibase manage
 * their own connection), so it needs no runtime {@link javax.sql.DataSource} and is decoupled from
 * mansart-pool. The {@code @Default} datasource is migrated whenever {@code vidocq.pool.url} is set;
 * a named datasource is migrated only when it sets {@code vidocq.migration.<name>.locations}.
 *
 * <p>Its section of the startup report, {@code migration}, says what each datasource's migration did, and
 * raises {@value #NOTHING_FOUND} for one that found no migration and has none in its schema history.
 */
public final class MigrationExtension implements VidocqExtension, StartupReportContributor {

    private static final System.Logger LOG = System.getLogger(MigrationExtension.class.getName());

    /** A datasource whose migration found no migration, on a schema history that records none. */
    static final String NOTHING_FOUND = "VIDOCQ-MIG-001";

    private static final String PREFIX = "vidocq.migration.";
    private static final String POOL_PREFIX = "vidocq.pool.";
    private static final String LOCATIONS_SUFFIX = ".locations";
    private static final String DEFAULT_LOCATIONS_KEY = PREFIX + "locations";
    private static final String STRICT_KEY = PREFIX + "failOnMissingLocations";
    /** The keys read whatever the datasources; each named datasource adds its {@code <name>.locations}. */
    private static final Set<String> FIXED_KEYS = Set.of(
            PREFIX + "enabled", PREFIX + "engine", DEFAULT_LOCATIONS_KEY, STRICT_KEY);

    /** What one datasource's migration did: where it looked, and what the backend answered. */
    record Outcome(String dataSourceName, String locationsKey, List<String> locations, MigrationResult result) {}

    private final Supplier<List<SchemaMigrator>> backends;
    private SchemaMigrator migrator;
    private List<MigrationTarget> targets = List.of();
    private boolean disabled;
    private Set<String> configKeys = FIXED_KEYS;
    /** Written by {@link #beforeStart}, read by {@link #contribute}. */
    private volatile List<Outcome> outcomes = List.of();

    /** Called by {@link ServiceLoader}: the backends are the {@link SchemaMigrator}s on the path. */
    public MigrationExtension() {
        this(() -> {
            List<SchemaMigrator> found = new ArrayList<>();
            ServiceLoader.load(SchemaMigrator.class, MigrationExtension.class.getClassLoader()).forEach(found::add);
            return found;
        });
    }

    /** With the backends given, for the tests. */
    MigrationExtension(Supplier<List<SchemaMigrator>> backends) {
        this.backends = backends;
    }

    @Override public String name() { return "migration"; }
    @Override public int priority() { return 150; }
    @Override public String id() { return "migration"; }
    @Override public String title() { return "Schema migration"; }

    /**
     * {@code vidocq.migration.enabled}, {@code .engine}, {@code .locations}, {@code .failOnMissingLocations}
     * and the {@code vidocq.migration.<name>.locations} of each named datasource: exact keys, not a prefix,
     * so that a typo such as {@code vidocq.migration.location} is reported as read by nothing.
     */
    @Override
    public Set<String> configKeys() {
        return configKeys;
    }

    @Override
    public void configure(VidocqConfiguration cfg) {
        this.configKeys = declaredKeys(cfg);
        if (!cfg.property(PREFIX + "enabled").map(Boolean::parseBoolean).orElse(true)) {
            this.disabled = true;
            LOG.log(System.Logger.Level.DEBUG, "Migration disabled (vidocq.migration.enabled=false)");
            return;
        }
        this.targets = buildTargets(cfg);
        if (targets.isEmpty()) {
            return;
        }
        this.migrator = select(backends.get(), cfg.property(PREFIX + "engine"));
        LOG.log(System.Logger.Level.INFO,
                "Migration configured: engine=" + migrator.engine()
                        + " datasources=" + targets.stream().map(MigrationTarget::dataSourceName).toList());
    }

    @Override
    public void beforeStart(VaubanContainerBuilder builder) {
        if (migrator == null || targets.isEmpty()) {
            return;
        }
        List<Outcome> done = new ArrayList<>();
        for (MigrationTarget t : targets) {
            MigrationResult r = migrator.migrate(t); // throws on failure → boot aborts (fail fast)
            done.add(new Outcome(t.dataSourceName(), locationsKey(t), locations(t), r));
            LOG.log(System.Logger.Level.INFO,
                    "Migration done: datasource=" + t.dataSourceName()
                            + " applied=" + r.applied()
                            + " version=" + r.version());
        }
        this.outcomes = List.copyOf(done);
    }

    /**
     * The engine and each datasource's outcome, the locations at {@link Verbosity#DETAILED}, and a
     * {@value #NOTHING_FOUND} anomaly, at every level, for each datasource that was not migrated at all.
     * Reads what {@link #beforeStart} kept, nothing else.
     */
    @Override
    public void contribute(StartupReportContext context, StartupReportSection section) {
        if (disabled) {
            section.summary("disabled: vidocq.migration.enabled=false");
            return;
        }
        List<Outcome> done = outcomes;
        if (migrator == null || done.isEmpty()) {
            section.summary("idle: no vidocq.pool[.<name>].url");
            return;
        }
        section.summary(migrator.engine() + ": " + done.stream()
                .map(o -> o.result().nothingFound()
                        ? o.dataSourceName() + " not migrated"
                        : o.dataSourceName() + " " + o.result().applied() + " applied, version " + o.result().version())
                .collect(Collectors.joining("; ")));
        if (context.verbosity() == Verbosity.DETAILED) {
            for (Outcome o : done) {
                section.row(o.dataSourceName(), o.result().applied() + " applied, version " + o.result().version());
                section.list(o.dataSourceName() + " locations", o.locations());
            }
        }
        for (Outcome o : done) {
            if (o.result().nothingFound()) {
                section.anomaly(NOTHING_FOUND,
                        "datasource " + o.dataSourceName() + ": no migration found in " + o.locations()
                                + " and none in its schema history; the schema was not migrated",
                        "Check " + o.locationsKey() + " and that the scripts are in the application");
            }
        }
    }

    // ── package-private helpers (unit-tested) ─────────────────────────────────

    static List<MigrationTarget> buildTargets(VidocqConfiguration cfg) {
        List<MigrationTarget> result = new ArrayList<>();
        boolean strict = cfg.property(STRICT_KEY).map(Boolean::parseBoolean).orElse(false);
        if (cfg.property(POOL_PREFIX + "url").isPresent()) {
            result.add(target(cfg, "default", POOL_PREFIX, DEFAULT_LOCATIONS_KEY, strict));
        }
        for (String name : namedWithLocations(cfg)) {
            String poolPrefix = POOL_PREFIX + name + ".";
            if (cfg.property(poolPrefix + "url").isPresent()) {
                result.add(target(cfg, name, poolPrefix, PREFIX + name + LOCATIONS_SUFFIX, strict));
            }
        }
        return result;
    }

    /** {@link #FIXED_KEYS} and the {@code vidocq.migration.<name>.locations} of every named datasource. */
    static Set<String> declaredKeys(VidocqConfiguration cfg) {
        Set<String> keys = new TreeSet<>(FIXED_KEYS);
        for (String name : namedWithLocations(cfg)) {
            keys.add(PREFIX + name + LOCATIONS_SUFFIX);
        }
        return Set.copyOf(keys);
    }

    private static MigrationTarget target(VidocqConfiguration cfg, String name,
                                          String poolPrefix, String locKey, boolean strict) {
        return new MigrationTarget(name,
                cfg.property(poolPrefix + "url").orElseThrow(),
                cfg.property(poolPrefix + "username").orElse(null),
                cfg.property(poolPrefix + "password").orElse(null),
                cfg.property(locKey).map(s -> List.of(s.split("\\s*,\\s*"))).orElse(List.of()),
                strict);
    }

    private static TreeSet<String> namedWithLocations(VidocqConfiguration cfg) {
        TreeSet<String> names = new TreeSet<>();
        for (String key : cfg.propertyNames()) {
            // vidocq.migration.locations belongs to the @Default datasource: it matches both ends, but has
            // no <name> between them (PREFIX and LOCATIONS_SUFFIX share its dot), and substring would throw.
            if (key.length() > PREFIX.length() + LOCATIONS_SUFFIX.length()
                    && key.startsWith(PREFIX) && key.endsWith(LOCATIONS_SUFFIX)) {
                String name = key.substring(PREFIX.length(), key.length() - LOCATIONS_SUFFIX.length());
                if (!name.isEmpty() && name.indexOf('.') < 0) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    /** The key that sets the locations of {@code t}, as the hint of {@value #NOTHING_FOUND} names it. */
    private static String locationsKey(MigrationTarget t) {
        return "default".equals(t.dataSourceName()) ? DEFAULT_LOCATIONS_KEY
                : PREFIX + t.dataSourceName() + LOCATIONS_SUFFIX;
    }

    /** The locations {@code t} was migrated from: its own, or the backend's default. */
    private List<String> locations(MigrationTarget t) {
        return t.locations().isEmpty() ? migrator.defaultLocations() : t.locations();
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
