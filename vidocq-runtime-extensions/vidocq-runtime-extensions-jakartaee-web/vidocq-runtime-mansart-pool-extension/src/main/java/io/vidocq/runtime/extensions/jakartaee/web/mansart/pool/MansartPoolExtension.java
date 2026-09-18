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
package io.vidocq.runtime.extensions.jakartaee.web.mansart.pool;

import io.vidocq.mansart.pool.PoolConfig;
import io.vidocq.mansart.pool.PoolMetrics;
import io.vidocq.mansart.pool.ValidationMode;
import io.vidocq.mansart.pool.core.MansartDataSource;
import io.vidocq.runtime.spi.ExtensionContext;
import io.vidocq.runtime.spi.VidocqConfiguration;
import io.vidocq.runtime.spi.VidocqExtension;
import io.vidocq.runtime.spi.devconsole.Chart;
import io.vidocq.runtime.spi.devconsole.DevConsolePanel;
import io.vidocq.runtime.spi.devconsole.PanelSample;
import io.vidocq.runtime.spi.devconsole.Series;
import io.vidocq.runtime.spi.devconsole.Unit;
import io.vidocq.runtime.spi.report.LaunchMode;
import io.vidocq.runtime.spi.report.StartupReportContext;
import io.vidocq.runtime.spi.report.StartupReportSection;
import io.vidocq.runtime.spi.report.Verbosity;
import io.vidocq.vauban.core.container.VaubanContainerBuilder;
import jakarta.enterprise.inject.literal.NamedLiteral;
import jakarta.enterprise.inject.spi.BeanManager;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Publishes a {@link MansartDataSource} as the {@code @Default} CDI {@link DataSource} bean for any
 * Vidocq application that opts in via the {@code vidocq.pool.url} property.
 *
 * <p>Activation is opt-in: the extension is no-op when {@code vidocq.pool.url} is missing, so this
 * jar is safe to drop on the classpath of an app that wires its own DataSource.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>{@code configure(VidocqConfiguration)} reads {@code vidocq.pool.*} into a {@link PoolConfig}
 *       and validates it via {@link PoolConfig.Builder#build()} — early failure on misconfiguration.</li>
 *   <li>{@code beforeStart(VaubanContainerBuilder)} instantiates the {@link MansartDataSource} and
 *       registers it with Vauban as the singleton {@link DataSource} factory. The pool is opened
 *       here, not in {@code onStart}, so any later extension that depends on a {@link DataSource}
 *       (mansart-data, mansart-persistence, …) sees it ready when the CDI container boots.</li>
 *   <li>{@code onStart(ExtensionContext)} notes what the startup report will flag: a pool whose
 *       {@code minIdle} pre-fill opened nothing, a named pool no {@code @Named} {@link DataSource} bean
 *       serves.</li>
 *   <li>{@code onStop} first forgets the pools the dev console reads, then drains each via
 *       {@link MansartDataSource#close()} — housekeeper stops, idle connections close, in-flight users get
 *       {@link io.vidocq.mansart.pool.PoolException.Reason#POOL_CLOSED}.</li>
 * </ol>
 *
 * <p>Priority {@code 200} — runs after the Chappe transport (100) so the JDBC driver class load
 * is not on the latency-critical port-binding path, but well before any extension that wires
 * repositories ({@code mansart-data}, priority ~300) or HTTP routing ({@code Cassini}, priority 500).
 *
 * <p>Recognized properties (every duration is parsed via {@link Duration#parse(CharSequence)},
 * ISO-8601, e.g. {@code PT5S} = 5 seconds, {@code PT0S} = leak detection disabled):
 *
 * <ul>
 *   <li>{@code vidocq.pool.url} — JDBC URL. <b>Required</b> to activate the extension.</li>
 *   <li>{@code vidocq.pool.username}, {@code vidocq.pool.password} — credentials.</li>
 *   <li>{@code vidocq.pool.minIdle} (default 0), {@code vidocq.pool.maxSize} (default 10).</li>
 *   <li>{@code vidocq.pool.acquireTimeout} (default {@code PT5S}).</li>
 *   <li>{@code vidocq.pool.idleTimeout} (default {@code PT10M}).</li>
 *   <li>{@code vidocq.pool.maxLifetime} (default {@code PT30M}).</li>
 *   <li>{@code vidocq.pool.validationTimeout} (default {@code PT1S}).</li>
 *   <li>{@code vidocq.pool.validation} — {@code NEVER | ON_BORROW | PERIODIC}.</li>
 *   <li>{@code vidocq.pool.validationQuery} — when set, default validation switches to {@code ON_BORROW}.</li>
 *   <li>{@code vidocq.pool.leakDetectionThreshold} (default {@code PT0S} = disabled).</li>
 * </ul>
 *
 * <p><b>Named datasources (multi-datasource).</b> Besides the {@code @Default} pool, every
 * {@code vidocq.pool.<name>.url} key (single {@code <name>} segment, no dots) opens a second pool
 * registered under {@code <name>} in {@link NamedDataSourceRegistry}. The matching
 * {@code @Named("<name>")} {@link javax.sql.DataSource} bean is the compile-time generated holder
 * ({@code vidocq-runtime-mansart-pool-datasources-codegen}); {@code mansart-data} routes
 * {@code @Repository(dataStore = "<name>")} to it. The same {@code vidocq.pool.*} suffixes apply,
 * prefixed {@code vidocq.pool.<name>.}.</p>
 *
 * <p><b>Startup report and dev console.</b> The extension is its own {@link DevConsolePanel}, so the core
 * finds its section of the startup report with no second declaration, and the dev console shows that
 * section live. The unnamed pool is labelled {@value #DEFAULT_LABEL}, which no named pool can be mistaken
 * for, a pool named {@code default} included; named pools follow in name order.
 * <ul>
 *   <li><b>Boot facts</b>, {@link #contribute}: {@code 2 pools (@Default, audit), 12 connections max},
 *       then, per pool, its URL without credentials ({@link JdbcUrls}) and its user in a {@code dev}
 *       launch, or only the database kind, such as {@code h2 mem}, otherwise; its size, timeouts, checks,
 *       XA class and, in {@code dev}, the dev service that provided it; whether a password and URL
 *       credentials are configured, never what they are. {@link PoolConfig#toString()} prints the
 *       password: it is never rendered nor logged. Anomalies {@code MANSART-POOL-001} (the {@code minIdle}
 *       pre-fill opened nothing) and {@code MANSART-POOL-002} (a named pool with no bean).</li>
 *   <li><b>Live values</b>, {@link #sample}: one group per pool, read from
 *       {@link MansartDataSource#snapshot()}, lock-free and without I/O — the {@code active} and
 *       {@code idle} connections out of {@code maxSize}, the borrowers {@code waiting} (an estimate), the
 *       {@code borrows}, {@code timeouts} and {@code leaks} so far ({@code leak detection off} when it is),
 *       and the {@code mean-borrow} time, a mean over the pool's whole life, wait and connection opening
 *       included, which is shown as a number and never plotted.</li>
 *   <li><b>Charts</b>, per pool: {@code connections} (active, idle stacked on it, waiting, and the
 *       {@code maxSize} ceiling) and {@code throughput} (borrows and timeouts per second).</li>
 * </ul>
 * Two readings of those gauges are wrong. {@code maxSize - active - idle} is not free capacity: a
 * borrower that holds a permit while its connection is still being opened is counted in none of them.
 * And a snapshot is not atomic: each figure is read on its own, so {@code active + idle} can dip, or
 * briefly exceed what the pool holds, while a connection moves between them.
 *
 * <p>{@link #sample} runs on the console's request threads while a dev reload may be stopping this
 * extension: it reads one {@code volatile} immutable list of the open pools, published once they are all
 * open and emptied before any is closed.
 */
public final class MansartPoolExtension implements VidocqExtension, DevConsolePanel {

    private static final System.Logger LOG = System.getLogger(MansartPoolExtension.class.getName());

    private static final String PREFIX     = "vidocq.pool.";
    private static final String URL_SUFFIX = ".url";
    /**
     * Where {@code vidocq:dev} says which dev service provided a key: {@code vidocq.dev.provided.<key>=<id>}, such
     * as {@code vidocq.dev.provided.vidocq.pool.audit.url=postgres}.
     */
    private static final String DEV_PROVIDED = "vidocq.dev.provided.";
    /** The label of the pool of {@code vidocq.pool.url}, the pool injected as {@code @Default}; a named pool has its name. */
    static final String DEFAULT_LABEL = "@Default";

    private static final List<Chart> CHARTS = List.of(
            new Chart("connections", "Connections", List.of(Series.area("active"), Series.stacked("idle"),
                    Series.line("waiting"), Series.ceiling("active"))),
            new Chart("throughput", "Throughput", List.of(Series.rate("borrows"), Series.rate("timeouts"))));

    /** The {@code @Default} pool config (from {@code vidocq.pool.*}); {@code null} when not opted in. */
    private PoolConfig poolConfig;
    /** Open {@code @Default} pool, or {@code null} when idle. */
    private MansartDataSource pool;
    /** Named pool configs keyed by datasource name (from {@code vidocq.pool.<name>.*}). */
    private Map<String, PoolConfig> namedConfigs = Map.of();
    /** Open named pools, keyed by datasource name. */
    private final Map<String, MansartDataSource> namedPools = new LinkedHashMap<>();
    /** The dev service that provided a pool's URL, keyed by the pool's key prefix, {@code vidocq.pool[.<name>].}. */
    private Map<String, String> devServices = Map.of();

    /**
     * The open pools, as the report and the dev console show them: an immutable list published at the end of
     * {@code beforeStart}, once every pool is open, and emptied first thing in {@code onStop}, before any is closed.
     * {@link #sample} reads it once per call, from the console's request threads.
     */
    private volatile List<PoolView> views = List.of();
    /** The labels of the named pools no {@code @Named} {@link DataSource} bean serves, found in {@code onStart}. */
    private volatile Set<String> namedWithoutBean = Set.of();
    /** The labels of the pools whose {@code minIdle} pre-fill opened nothing, found in {@code onStart}. */
    private volatile Set<String> prefillFailed = Set.of();

    /**
     * One open pool as the report and the dev console show it, built once when the pool opens. It holds no secret:
     * the URL is redacted, and the password is only ever tested for presence through {@link MansartDataSource#config()}.
     *
     * @param label          {@value #DEFAULT_LABEL} for the pool of {@code vidocq.pool.url}, its name for a named pool
     * @param isDefault      whether it is the pool of {@code vidocq.pool.url}
     * @param pool           the pool: its {@linkplain MansartDataSource#config() configuration} and its
     *                       {@linkplain MansartDataSource#snapshot() counters}
     * @param safeUrl        its URL without credentials, shown in a {@code dev} launch only
     * @param kind           the database it reaches, such as {@code h2 mem}, shown outside {@code dev}
     * @param urlCredentials whether its URL carried credentials, which {@code safeUrl} no longer has
     * @param devService     {@code dev service <id>, <host:port>} when {@code vidocq:dev} provided its URL,
     *                       {@code null} otherwise
     */
    private record PoolView(String label, boolean isDefault, MansartDataSource pool, String safeUrl, String kind,
                            boolean urlCredentials, String devService) {}

    @Override
    public String name() {
        return "mansart-pool";
    }

    @Override
    public String id() {
        return "mansart-pool";
    }

    @Override
    public String title() {
        return "Mansart pools";
    }

    @Override
    public int priority() {
        return 200;
    }

    @Override
    public void configure(VidocqConfiguration vidocqConfig) {
        this.poolConfig   = buildPoolConfig(vidocqConfig, PREFIX);   // @Default (null if no url)
        this.namedConfigs = discoverNamedConfigs(vidocqConfig);      // vidocq.pool.<name>.*
        this.devServices  = devServices(vidocqConfig);               // vidocq.dev.provided.vidocq.pool[.<name>].url
        if (poolConfig == null && namedConfigs.isEmpty()) {
            LOG.log(System.Logger.Level.DEBUG,
                    "Mansart pool extension idle: no vidocq.pool[.<name>].url set");
            return;
        }
        if (poolConfig != null) {
            LOG.log(System.Logger.Level.INFO,
                    "Mansart pool configured (@Default): maxSize=" + poolConfig.maxSize()
                            + " validation=" + poolConfig.validation());
        }
        if (!namedConfigs.isEmpty()) {
            LOG.log(System.Logger.Level.INFO,
                    "Mansart named pools configured: " + namedConfigs.keySet());
        }
    }

    @Override
    public void beforeStart(VaubanContainerBuilder builder) {
        List<PoolView> opened = new ArrayList<>();
        if (poolConfig != null) {
            this.pool = MansartDataSource.of(poolConfig);
            // Publish to the holder BEFORE adding it as a bean class so the holder has its singleton
            // ready by the time Vauban's bean discovery enumerates it.
            MansartPoolHolder.INSTANCE = this.pool;
            builder.addBeanClass(MansartPoolHolder.class);
            opened.add(view(DEFAULT_LABEL, true, PREFIX, pool));
        }
        // Named pools only feed the registry: the @Named DataSource holder beans are generated by
        // the optional vidocq-runtime-mansart-pool-datasources-codegen into the application module
        // and scanned by Vauban like any @Named @Singleton bean — the extension does not add them.
        for (Map.Entry<String, PoolConfig> e : namedConfigs.entrySet()) {
            MansartDataSource ds = MansartDataSource.of(e.getValue());
            namedPools.put(e.getKey(), ds);
            NamedDataSourceRegistry.register(e.getKey(), ds);
            opened.add(view(e.getKey(), false, PREFIX + e.getKey() + ".", ds));
        }
        views = List.copyOf(opened);
    }

    /**
     * Notes, once the container is up, what the startup report flags: the pools whose {@code minIdle} pre-fill opened
     * no connection, since the pool gives up at its first failure without a word, and the named pools that no
     * {@code @Named} {@link DataSource} bean serves, which fail only on their first use.
     */
    @Override
    public void onStart(ExtensionContext context) {
        List<PoolView> opened = views;
        Set<String> failed = new HashSet<>();
        for (PoolView v : opened) {
            PoolMetrics m = v.pool().snapshot();
            if (v.pool().config().minIdle() > 0 && m.idle() + m.active() == 0) {
                failed.add(v.label());
            }
        }
        prefillFailed = Set.copyOf(failed);
        namedWithoutBean = namedWithoutBean(opened, context);
    }

    /**
     * The labels of the named pools with no {@code @Named("<name>") DataSource} bean. A container that cannot answer
     * flags none: the report never guesses.
     */
    private static Set<String> namedWithoutBean(List<PoolView> opened, ExtensionContext context) {
        if (opened.stream().allMatch(PoolView::isDefault)) {
            return Set.of();
        }
        try {
            BeanManager beans = context.beanManager();
            Set<String> missing = new HashSet<>();
            for (PoolView v : opened) {
                if (!v.isDefault() && beans.getBeans(DataSource.class, NamedLiteral.of(v.label())).isEmpty()) {
                    missing.add(v.label());
                }
            }
            return Set.copyOf(missing);
        } catch (RuntimeException unknown) {
            LOG.log(System.Logger.Level.DEBUG,
                    "Mansart pool: cannot tell which named pools have a @Named DataSource bean", unknown);
            return Set.of();
        }
    }

    @Override
    public void onStop() {
        // First, before any pool is closed: a dev console poll from now on reads no pool.
        views = List.of();
        namedWithoutBean = Set.of();
        prefillFailed = Set.of();
        for (Map.Entry<String, MansartDataSource> e : namedPools.entrySet()) {
            try {
                e.getValue().close();
            } catch (RuntimeException ex) {
                LOG.log(System.Logger.Level.WARNING,
                        "Error closing Mansart named pool '" + e.getKey() + "'", ex);
            } finally {
                NamedDataSourceRegistry.unregister(e.getKey());
            }
        }
        namedPools.clear();
        if (pool != null) {
            try {
                pool.close();
                LOG.log(System.Logger.Level.INFO, "Mansart pool closed");
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "Error closing Mansart pool", e);
            } finally {
                pool = null;
                MansartPoolHolder.INSTANCE = null;
            }
        }
    }

    /**
     * Builds a {@link PoolConfig} from the {@code <prefix>*} keys, or {@code null} when
     * {@code <prefix>url} is absent. The validation rules of {@link PoolConfig.Builder#build()}
     * surface eagerly, so a misconfiguration fails during {@code configure}.
     */
    private static PoolConfig buildPoolConfig(VidocqConfiguration cfg, String prefix) {
        Optional<String> url = cfg.property(prefix + "url");
        if (url.isEmpty()) return null;
        PoolConfig.Builder b = PoolConfig.builder().jdbcUrl(url.get());
        cfg.property(prefix + "username").ifPresent(b::username);
        cfg.property(prefix + "password").ifPresent(b::password);
        cfg.property(prefix + "minIdle").map(Integer::parseInt).ifPresent(b::minIdle);
        cfg.property(prefix + "maxSize").map(Integer::parseInt).ifPresent(b::maxSize);
        cfg.property(prefix + "acquireTimeout").map(Duration::parse).ifPresent(b::acquireTimeout);
        cfg.property(prefix + "idleTimeout").map(Duration::parse).ifPresent(b::idleTimeout);
        cfg.property(prefix + "maxLifetime").map(Duration::parse).ifPresent(b::maxLifetime);
        cfg.property(prefix + "validationTimeout").map(Duration::parse).ifPresent(b::validationTimeout);
        cfg.property(prefix + "validation").map(s -> ValidationMode.valueOf(s.trim().toUpperCase()))
                .ifPresent(b::validation);
        cfg.property(prefix + "validationQuery").ifPresent(b::validationQuery);
        cfg.property(prefix + "leakDetectionThreshold").map(Duration::parse)
                .ifPresent(b::leakDetectionThreshold);
        // MANSART-007 phase 2 — `xa=true` exposes the driver's XADataSource through the pool
        // (unwrap), so the JTA bridge enlists real XA branches. The implementation class is
        // auto-detected from the JDBC URL for the bundled dialects and overridable with
        // `xaDataSourceClass` for any other driver.
        String xaClass = cfg.property(prefix + "xaDataSourceClass").orElse(null);
        boolean xa = cfg.property(prefix + "xa").map(Boolean::parseBoolean).orElse(xaClass != null);
        if (xa) {
            String resolved = xaClass != null ? xaClass : autoDetectXaDataSourceClass(url.get());
            if (resolved == null) {
                throw new IllegalArgumentException(
                        "Property '" + prefix + "xa=true' but the XADataSource class cannot be"
                                + " derived from the URL '" + JdbcUrls.redact(url.get()) + "' — set '"
                                + prefix + "xaDataSourceClass' explicitly");
            }
            b.xaDataSourceClassName(resolved);
        }
        return b.build();
    }

    /** Known dialects' XADataSource implementations, keyed by JDBC URL scheme. */
    private static String autoDetectXaDataSourceClass(String jdbcUrl) {
        if (jdbcUrl.startsWith("jdbc:h2:")) return "org.h2.jdbcx.JdbcDataSource";
        if (jdbcUrl.startsWith("jdbc:postgresql:")) return "org.postgresql.xa.PGXADataSource";
        return null;
    }

    /**
     * Discovers named datasources by scanning every config key for {@code vidocq.pool.<name>.url}
     * (single {@code <name>} segment, no dots), excluding the {@code @Default} {@code vidocq.pool.url}.
     * Names are processed in a stable, sorted order for deterministic startup and logs.
     */
    private static Map<String, PoolConfig> discoverNamedConfigs(VidocqConfiguration cfg) {
        TreeSet<String> names = new TreeSet<>();
        for (String key : cfg.propertyNames()) {
            namedDatasourceOf(key).ifPresent(names::add);
        }
        if (names.isEmpty()) return Map.of();
        LinkedHashMap<String, PoolConfig> out = new LinkedHashMap<>();
        for (String name : names) {
            out.put(name, buildPoolConfig(cfg, PREFIX + name + "."));
        }
        return out;
    }

    /**
     * Extracts the datasource name from a {@code vidocq.pool.<name>.url} key, or empty when the key
     * is the {@code @Default} {@code vidocq.pool.url}, is nested ({@code <name>} contains a dot), or
     * is not a {@code .url} key at all. Mirrors the codegen's parsing so both sources agree.
     */
    private static Optional<String> namedDatasourceOf(String key) {
        if (!key.startsWith(PREFIX) || !key.endsWith(URL_SUFFIX)) return Optional.empty();
        int begin = PREFIX.length();
        int end   = key.length() - URL_SUFFIX.length();
        if (begin >= end) return Optional.empty();            // exactly vidocq.pool.url (the @Default)
        String name = key.substring(begin, end);
        if (name.indexOf('.') >= 0) return Optional.empty();  // vidocq.pool.a.b.url → not single-segment
        return Optional.of(name);
    }

    /**
     * The dev service that provided each pool's URL, from the markers {@code vidocq:dev} passes to the application,
     * keyed by the pool's key prefix. Only the provider's id is read: what it injected, the password included, is
     * read as any other configuration.
     */
    private Map<String, String> devServices(VidocqConfiguration cfg) {
        Map<String, String> out = new HashMap<>();
        List<String> prefixes = new ArrayList<>();
        if (poolConfig != null) {
            prefixes.add(PREFIX);
        }
        namedConfigs.keySet().forEach(name -> prefixes.add(PREFIX + name + "."));
        for (String prefix : prefixes) {
            cfg.property(DEV_PROVIDED + prefix + "url").map(String::strip).filter(id -> !id.isEmpty())
                    .ifPresent(id -> out.put(prefix, id));
        }
        return Map.copyOf(out);
    }

    /** What the report and the dev console show of an open pool, computed once. */
    private PoolView view(String label, boolean isDefault, String prefix, MansartDataSource ds) {
        String jdbcUrl = ds.config().jdbcUrl();
        JdbcUrls.Redacted url = JdbcUrls.redacted(jdbcUrl);
        String provider = devServices.get(prefix);
        String devService = null;
        if (provider != null) {
            String authority = JdbcUrls.authority(url.url());
            devService = "dev service " + provider + (authority == null ? "" : ", " + authority);
        }
        return new PoolView(label, isDefault, ds, url.url(), JdbcUrls.kind(jdbcUrl), url.credentialsRemoved(),
                devService);
    }

    /**
     * The pools of this boot, once per boot, from memory: the summary, then at {@link Verbosity#DETAILED} the rows of
     * each pool, then the anomalies found in {@code onStart}. The URL and the user are configuration values, written in
     * a {@link LaunchMode#DEV dev} launch only; outside it, the pool's row says what database it reaches.
     */
    @Override
    public void contribute(StartupReportContext context, StartupReportSection section) {
        List<PoolView> opened = views;
        if (opened.isEmpty()) {
            section.summary("idle: no vidocq.pool[.<name>].url");
            return;
        }
        int max = opened.stream().mapToInt(v -> v.pool().config().maxSize()).sum();
        section.summary(opened.size() + (opened.size() == 1 ? " pool (" : " pools (")
                + opened.stream().map(PoolView::label).collect(Collectors.joining(", ")) + "), "
                + max + (max == 1 ? " connection max" : " connections max"));
        if (context.verbosity() == Verbosity.DETAILED) {
            boolean dev = context.launchMode() == LaunchMode.DEV;
            for (PoolView v : opened) {
                rows(section, v, dev);
            }
        }
        Set<String> failed = prefillFailed;
        Set<String> beanless = namedWithoutBean;
        for (PoolView v : opened) {
            String label = v.label();
            if (failed.contains(label)) {
                int minIdle = v.pool().config().minIdle();
                section.anomaly("MANSART-POOL-001",
                        "Pool '" + label + "' opened none of the " + minIdle
                                + (minIdle == 1 ? " connection" : " connections")
                                + " its minIdle asks for at boot: the pool gave up at the first failure and opens"
                                + " connections on demand only",
                        "Check " + (v.isDefault() ? PREFIX : PREFIX + label + ".") + "url, the credentials and the"
                                + " JDBC driver on the module path");
            }
            if (!v.isDefault() && beanless.contains(label)) {
                section.anomaly("MANSART-POOL-002",
                        "Named pool '" + label + "' is open, but no @Named(\"" + label + "\") DataSource bean serves"
                                + " it: an injection or a repository routed to it fails on first use",
                        "Add vidocq-runtime-mansart-pool-datasources-codegen to the annotationProcessorPaths of the"
                                + " application");
            }
        }
    }

    /** The rows of one pool, each key starting with its label, which is how the dev console groups them. */
    private static void rows(StartupReportSection section, PoolView v, boolean dev) {
        PoolConfig c = v.pool().config();
        String label = v.label();
        section.row(label, dev ? v.safeUrl() : v.kind());
        if (dev) {
            section.row(label + " user", c.username() != null ? c.username() : "not set");
        }
        String validation = c.validation() == ValidationMode.NEVER
                ? "validation NEVER"
                : "validation " + c.validation() + " " + c.validationTimeout();
        String leaks = c.leakDetectionThreshold().isZero()
                ? "leaks off"
                : "leaks after " + c.leakDetectionThreshold();
        section.row(label + " size", "min idle " + c.minIdle() + " (boot only), max " + c.maxSize())
                .row(label + " timeouts", "acquire " + c.acquireTimeout() + ", idle " + c.idleTimeout()
                        + ", lifetime " + c.maxLifetime())
                .row(label + " checks", validation + ", " + leaks);
        if (c.xaDataSourceClassName() != null) {
            section.row(label + " xa", c.xaDataSourceClassName());
        }
        if (dev && v.devService() != null) {
            section.row(label + " source", v.devService());
        }
        section.secret(label + " password", c.password() != null);
        if (v.urlCredentials()) {
            section.secret(label + " url credentials", true);
        }
        // the keys only, never the values: a driver property may be a password
        section.list(label + " driver properties", new TreeSet<>(c.driverProperties().keySet()));
    }

    @Override
    public List<Chart> charts() {
        return CHARTS;
    }

    /**
     * One group per open pool, from its {@link MansartDataSource#snapshot()}: counters kept in memory, read without a
     * lock and without I/O, in two small allocations. Once {@code onStop} has begun, no pool.
     */
    @Override
    public void sample(PanelSample sample) {
        List<PoolView> opened = views;
        for (PoolView v : opened) {
            PoolConfig c = v.pool().config();
            PoolMetrics m = v.pool().snapshot();
            PanelSample pool = sample.group(v.label())
                    .gauge("active", m.active(), c.maxSize(), Unit.COUNT)
                    .gauge("idle", m.idle(), c.maxSize(), Unit.COUNT)
                    .gauge("waiting", m.waiting(), Unit.COUNT)
                    .counter("borrows", m.totalBorrows(), Unit.COUNT)
                    .counter("timeouts", m.totalTimeouts(), Unit.COUNT);
            if (c.leakDetectionThreshold().isZero()) {
                pool.absent("leaks", "leak detection off");
            } else {
                pool.counter("leaks", m.totalLeaks(), Unit.COUNT);
            }
            if (m.totalBorrows() == 0) {
                pool.absent("mean-borrow", "no borrow yet");
            } else {
                pool.duration("mean-borrow", m.meanBorrowDuration());
            }
        }
    }

    /** Visible for tests. */
    PoolConfig poolConfig() {
        return poolConfig;
    }

    /** Visible for tests. */
    MansartDataSource pool() {
        return pool;
    }

    /** Visible for tests. */
    Map<String, PoolConfig> namedConfigs() {
        return namedConfigs;
    }
}
