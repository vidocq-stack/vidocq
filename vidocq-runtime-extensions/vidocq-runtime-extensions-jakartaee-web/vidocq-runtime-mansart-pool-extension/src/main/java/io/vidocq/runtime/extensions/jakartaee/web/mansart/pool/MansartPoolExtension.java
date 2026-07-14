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
import io.vidocq.mansart.pool.ValidationMode;
import io.vidocq.mansart.pool.core.MansartDataSource;
import io.vidocq.runtime.spi.VidocqConfiguration;
import io.vidocq.runtime.spi.VidocqExtension;
import io.vidocq.vauban.core.container.VaubanContainerBuilder;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

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
 *   <li>{@code onStop} drains the pool via {@link MansartDataSource#close()} — housekeeper stops,
 *       idle connections close, in-flight users get {@link io.vidocq.mansart.pool.PoolException.Reason#POOL_CLOSED}.</li>
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
 */
public final class MansartPoolExtension implements VidocqExtension {

    private static final System.Logger LOG = System.getLogger(MansartPoolExtension.class.getName());

    private static final String PREFIX     = "vidocq.pool.";
    private static final String URL_SUFFIX = ".url";

    /** The {@code @Default} pool config (from {@code vidocq.pool.*}); {@code null} when not opted in. */
    private PoolConfig poolConfig;
    /** Open {@code @Default} pool, or {@code null} when idle. */
    private MansartDataSource pool;
    /** Named pool configs keyed by datasource name (from {@code vidocq.pool.<name>.*}). */
    private Map<String, PoolConfig> namedConfigs = Map.of();
    /** Open named pools, keyed by datasource name. */
    private final Map<String, MansartDataSource> namedPools = new LinkedHashMap<>();

    @Override
    public String name() {
        return "mansart-pool";
    }

    @Override
    public int priority() {
        return 200;
    }

    @Override
    public void configure(VidocqConfiguration vidocqConfig) {
        this.poolConfig   = buildPoolConfig(vidocqConfig, PREFIX);   // @Default (null if no url)
        this.namedConfigs = discoverNamedConfigs(vidocqConfig);      // vidocq.pool.<name>.*
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
        if (poolConfig != null) {
            this.pool = MansartDataSource.of(poolConfig);
            // Publish to the holder BEFORE adding it as a bean class so the holder has its singleton
            // ready by the time Vauban's bean discovery enumerates it.
            MansartPoolHolder.INSTANCE = this.pool;
            builder.addBeanClass(MansartPoolHolder.class);
        }
        // Named pools only feed the registry: the @Named DataSource holder beans are generated by
        // the optional vidocq-runtime-mansart-pool-datasources-codegen into the application module
        // and scanned by Vauban like any @Named @Singleton bean — the extension does not add them.
        for (Map.Entry<String, PoolConfig> e : namedConfigs.entrySet()) {
            MansartDataSource ds = MansartDataSource.of(e.getValue());
            namedPools.put(e.getKey(), ds);
            NamedDataSourceRegistry.register(e.getKey(), ds);
        }
    }

    @Override
    public void onStop() {
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
                                + " derived from the URL '" + url.get() + "' — set '"
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
