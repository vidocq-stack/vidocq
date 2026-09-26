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

import io.vidocq.runtime.extensions.essentials.migration.live.MigrationControl;
import io.vidocq.runtime.extensions.essentials.migration.live.MigrationControl.LiveOutcome;
import io.vidocq.runtime.extensions.essentials.migration.live.MigrationLive;
import io.vidocq.runtime.spi.ExtensionContext;
import io.vidocq.runtime.spi.VidocqConfiguration;
import io.vidocq.runtime.spi.VidocqExtension;
import io.vidocq.runtime.spi.report.LaunchMode;
import io.vidocq.runtime.spi.report.StartupReportContext;
import io.vidocq.runtime.spi.report.StartupReportContributor;
import io.vidocq.runtime.spi.report.StartupReportSection;
import io.vidocq.runtime.spi.report.Verbosity;
import io.vidocq.vauban.core.container.VaubanContainerBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
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
 *
 * <p>Implements {@link MigrationControl} and publishes itself through {@link MigrationLive} once a migrator is
 * selected, cleared first in {@link #onStop}: the {@code migration} panel of the {@code -dev} companion module
 * reads it live, per datasource, its version, what the last run did, and, in a dev launch, the migrations applied
 * and pending, listed once after the boot's migration and again after each action, never while the page polls. In
 * a dev launch it offers two actions through that control: {@code migrate}, which applies the pending migrations
 * of a datasource without a restart, and {@code clean-and-migrate}, which drops every object of its schema and
 * migrates it again, refused, with nothing dropped, unless {@code vidocq.migration[.<name>].cleanDisabled} is
 * {@code false}. The password of a datasource reaches neither the page nor the log.
 */
public final class MigrationExtension implements VidocqExtension, StartupReportContributor, MigrationControl {

    private static final System.Logger LOG = System.getLogger(MigrationExtension.class.getName());

    /** A datasource whose migration found no migration, on a schema history that records none. */
    static final String NOTHING_FOUND = "VIDOCQ-MIG-001";

    private static final String PREFIX = "vidocq.migration.";
    private static final String POOL_PREFIX = "vidocq.pool.";
    private static final String LOCATIONS_SUFFIX = ".locations";
    private static final String DEFAULT_LOCATIONS_KEY = PREFIX + "locations";
    private static final String STRICT_KEY = PREFIX + "failOnMissingLocations";
    private static final String CLEAN_DISABLED_SUFFIX = "cleanDisabled";
    private static final String DEFAULT_CLEAN_DISABLED_KEY = PREFIX + CLEAN_DISABLED_SUFFIX;
    /**
     * The keys read whatever the datasources; each named datasource adds its {@code <name>.locations} and
     * {@code <name>.cleanDisabled}.
     */
    private static final Set<String> FIXED_KEYS = Set.of(
            PREFIX + "enabled", PREFIX + "engine", DEFAULT_LOCATIONS_KEY, STRICT_KEY, DEFAULT_CLEAN_DISABLED_KEY);

    /**
     * What one datasource's migration did: where it looked, what the backend answered, and what ran it.
     *
     * @param lastRun     what the panel says of the last run, such as {@code boot: 2 applied}
     * @param info        the migrations applied and pending, or {@code null} when they are not listed
     * @param infoAbsent  why they are not listed, when {@code info} is {@code null}
     */
    record Outcome(String dataSourceName, String locationsKey, List<String> locations, MigrationResult result,
                   boolean cleanDisabled, String lastRun, MigrationInfo info, String infoAbsent) {}

    private final Supplier<List<SchemaMigrator>> backends;
    private SchemaMigrator migrator;
    private List<MigrationTarget> targets = List.of();
    private boolean disabled;
    private Set<String> configKeys = FIXED_KEYS;
    /**
     * Written by {@link #beforeStart} and replaced by each action, read by {@link #contribute} and by
     * {@link #outcomes()}: an immutable list of immutable outcomes, cleared first in {@link #onStop}.
     */
    private volatile List<Outcome> outcomes = List.of();
    /** Set first in {@link #onStop}: an action that starts afterwards runs nothing. */
    private volatile boolean stopped;
    /** One monitor per datasource: two actions on one datasource run one after the other. */
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    /** Guards the replacement of one outcome in {@link #outcomes}. */
    private final Object outcomesLock = new Object();

    /** Called by {@link ServiceLoader}: the backends are the {@link SchemaMigrator}s on the path. */
    public MigrationExtension() {
        this(() -> {
            List<SchemaMigrator> found = new ArrayList<>();
            ServiceLoader.load(SchemaMigrator.class, MigrationExtension.class.getClassLoader()).forEach(found::add);
            return found;
        });
    }

    /**
     * With the backends given, for the tests — including those of the {@code -dev} companion module, which drives
     * this extension with a fake {@link SchemaMigrator} to test {@link MigrationControl} without a real backend.
     */
    public MigrationExtension(Supplier<List<SchemaMigrator>> backends) {
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
        MigrationLive.publish(this);
    }

    @Override
    public void beforeStart(VaubanContainerBuilder builder) {
        if (migrator == null || targets.isEmpty()) {
            return;
        }
        List<Outcome> done = new ArrayList<>();
        for (MigrationTarget t : targets) {
            MigrationResult r = migrator.migrate(t); // throws on failure → boot aborts (fail fast)
            done.add(new Outcome(t.dataSourceName(), locationsKey(t), locations(t), r, t.cleanDisabled(),
                    "boot: " + r.applied() + " applied", null, "listed in a dev launch only"));
            LOG.log(System.Logger.Level.INFO,
                    "Migration done: datasource=" + t.dataSourceName()
                            + " applied=" + r.applied()
                            + " version=" + r.version());
        }
        this.outcomes = List.copyOf(done);
    }

    /**
     * In a dev launch, lists the migrations of each datasource once, for the panel: it opens a connection per
     * datasource, which the panel's own poll never does.
     */
    @Override
    public void onStart(ExtensionContext context) {
        if (migrator == null || context.launchMode() != LaunchMode.DEV) {
            return;
        }
        for (MigrationTarget t : targets) {
            synchronized (lock(t)) {
                Outcome o = outcome(t.dataSourceName());
                if (o != null) {
                    replace(withInfo(o, t));
                }
            }
        }
    }

    /**
     * Clears {@link MigrationLive} first, so that a dev reload never shows the previous boot's control, then the
     * outcomes, so that the panel shows nothing of a stopping boot, and refuses any new action.
     */
    @Override
    public void onStop() {
        MigrationLive.clear();
        stopped = true;
        outcomes = List.of();
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

    // ── MigrationControl (read by the -dev panel through MigrationLive) ──────

    /**
     * Per datasource, its version, what the last run did, whether its schema may be cleaned, and the migrations
     * applied and pending when they were listed. Reads the outcomes kept in memory, nothing else: they were listed
     * by {@link #onStart} or by the last action.
     */
    @Override
    public List<LiveOutcome> outcomes() {
        return outcomes.stream()
                .map(o -> new LiveOutcome(o.dataSourceName(), o.result().version(), o.lastRun(), o.cleanDisabled(),
                        cleanDisabledKey(o.dataSourceName()), o.info(), o.infoAbsent()))
                .toList();
    }

    /** The datasources migrated, in configuration order; empty when there is no migrator. */
    @Override
    public List<String> dataSources() {
        return targets.stream().map(MigrationTarget::dataSourceName).toList();
    }

    /**
     * Applies the pending migrations of a datasource, as the boot did, and replaces its outcome.
     *
     * @param name the datasource
     * @return what was done, such as {@code default: 2 migrations applied, schema at version 3}
     */
    @Override
    public String migrateNow(String name) {
        MigrationTarget t = target(name);
        synchronized (lock(t)) {
            requireRunning();
            MigrationResult r = run(t, "migrate", () -> migrator.migrate(t));
            return name + ": " + applied(r) + ", schema at version " + r.version();
        }
    }

    /**
     * Drops every object in the schema of a datasource, then migrates it again, and replaces its outcome. Refused,
     * with nothing dropped, while its {@code cleanDisabled} key is not {@code false}, and when the backend cannot
     * clean.
     *
     * @param name the datasource
     * @return what was done, or why nothing was, naming the key to set
     */
    @Override
    public String cleanAndMigrate(String name) {
        MigrationTarget t = target(name);
        if (t.cleanDisabled()) {
            return name + ": clean refused, nothing dropped; set " + cleanDisabledKey(name) + "=false to allow it";
        }
        synchronized (lock(t)) {
            requireRunning();
            try {
                migrator.clean(t);
            } catch (UnsupportedOperationException unsupported) {
                return name + ": " + migrator.engine() + " cannot clean a schema, nothing dropped";
            }
            MigrationResult r = run(t, "clean-and-migrate", () -> migrator.migrate(t));
            return name + ": schema cleaned, " + applied(r) + ", schema at version " + r.version();
        }
    }

    /**
     * Migrates {@code t} and records the outcome with its migrations listed again. On a failure, the previous result
     * stays, the last run says it failed, by the exception's class only, and the exception goes on to the console.
     */
    private MigrationResult run(MigrationTarget t, String action, Supplier<MigrationResult> migration) {
        Outcome previous = outcome(t.dataSourceName());
        MigrationResult r;
        try {
            r = migration.get();
        } catch (RuntimeException failed) {
            if (previous != null) {
                replace(withInfo(new Outcome(previous.dataSourceName(), previous.locationsKey(),
                        previous.locations(), previous.result(), previous.cleanDisabled(),
                        action + ": failed (" + failed.getClass().getSimpleName() + ")", null, null), t));
            }
            throw failed;
        }
        LOG.log(System.Logger.Level.INFO, "Migration done by the dev console (" + action + "): datasource="
                + t.dataSourceName() + " applied=" + r.applied() + " version=" + r.version());
        replace(withInfo(new Outcome(t.dataSourceName(), locationsKey(t), locations(t), r, t.cleanDisabled(),
                action + ": " + r.applied() + " applied", null, null), t));
        return r;
    }

    /** {@code o} with the migrations of {@code t} listed now, or the reason they are not. */
    private Outcome withInfo(Outcome o, MigrationTarget t) {
        MigrationInfo info = null;
        String absent = null;
        try {
            info = migrator.info(t);
        } catch (UnsupportedOperationException unsupported) {
            absent = migrator.engine() + " cannot list the migrations";
        } catch (RuntimeException failed) {
            // the class only: the message of a JDBC failure may carry the URL, or worse
            absent = "not listed: " + failed.getClass().getSimpleName();
            LOG.log(System.Logger.Level.WARNING, "Migration: the migrations of datasource " + t.dataSourceName()
                    + " could not be listed: " + failed.getClass().getName());
        }
        return new Outcome(o.dataSourceName(), o.locationsKey(), o.locations(), o.result(), o.cleanDisabled(),
                o.lastRun(), info, absent);
    }

    /** Replaces the outcome of the same datasource in {@link #outcomes}, a new immutable list. */
    private void replace(Outcome o) {
        synchronized (outcomesLock) {
            if (stopped) {
                return;
            }
            List<Outcome> next = new ArrayList<>(outcomes);
            next.replaceAll(current -> current.dataSourceName().equals(o.dataSourceName()) ? o : current);
            outcomes = List.copyOf(next);
        }
    }

    private Outcome outcome(String name) {
        return outcomes.stream().filter(o -> o.dataSourceName().equals(name)).findFirst().orElse(null);
    }

    private MigrationTarget target(String name) {
        return targets.stream().filter(t -> t.dataSourceName().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no migrated datasource of that name"));
    }

    private Object lock(MigrationTarget t) {
        return locks.computeIfAbsent(t.dataSourceName(), n -> new Object());
    }

    private void requireRunning() {
        if (stopped) {
            throw new IllegalStateException("the migration extension is stopped");
        }
    }

    private static String applied(MigrationResult r) {
        return r.applied() + (r.applied() == 1 ? " migration applied" : " migrations applied");
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

    /**
     * {@link #FIXED_KEYS}, and the {@code vidocq.migration.<name>.locations} and {@code .cleanDisabled} of every named
     * datasource.
     */
    static Set<String> declaredKeys(VidocqConfiguration cfg) {
        Set<String> keys = new TreeSet<>(FIXED_KEYS);
        for (String name : namedWithLocations(cfg)) {
            keys.add(PREFIX + name + LOCATIONS_SUFFIX);
            keys.add(cleanDisabledKey(name));
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
                strict,
                // anything but false keeps the schema safe from clean
                cfg.property(cleanDisabledKey(name)).map(v -> !"false".equalsIgnoreCase(v.strip())).orElse(true));
    }

    /** The key that lets the schema of a datasource be cleaned: {@code vidocq.migration[.<name>].cleanDisabled}. */
    static String cleanDisabledKey(String name) {
        return "default".equals(name) ? DEFAULT_CLEAN_DISABLED_KEY : PREFIX + name + "." + CLEAN_DISABLED_SUFFIX;
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
