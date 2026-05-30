package io.vidocq.runtime.ext.mansart.pool;

import io.vidocq.mansart.pool.PoolConfig;
import io.vidocq.mansart.pool.ValidationMode;
import io.vidocq.mansart.pool.core.MansartDataSource;
import io.vidocq.runtime.spi.VidocqConfiguration;
import io.vidocq.runtime.spi.VidocqExtension;
import io.vidocq.vauban.core.container.VaubanContainerBuilder;

import java.time.Duration;
import java.util.Optional;

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
 */
public final class MansartPoolExtension implements VidocqExtension {

    private static final System.Logger LOG = System.getLogger(MansartPoolExtension.class.getName());

    private static final String P_URL                       = "vidocq.pool.url";
    private static final String P_USERNAME                  = "vidocq.pool.username";
    private static final String P_PASSWORD                  = "vidocq.pool.password";
    private static final String P_MIN_IDLE                  = "vidocq.pool.minIdle";
    private static final String P_MAX_SIZE                  = "vidocq.pool.maxSize";
    private static final String P_ACQUIRE_TIMEOUT           = "vidocq.pool.acquireTimeout";
    private static final String P_IDLE_TIMEOUT              = "vidocq.pool.idleTimeout";
    private static final String P_MAX_LIFETIME              = "vidocq.pool.maxLifetime";
    private static final String P_VALIDATION_TIMEOUT        = "vidocq.pool.validationTimeout";
    private static final String P_VALIDATION                = "vidocq.pool.validation";
    private static final String P_VALIDATION_QUERY          = "vidocq.pool.validationQuery";
    private static final String P_LEAK_DETECTION_THRESHOLD  = "vidocq.pool.leakDetectionThreshold";

    private PoolConfig        poolConfig;
    private MansartDataSource pool;

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
        Optional<String> url = vidocqConfig.property(P_URL);
        if (url.isEmpty()) {
            LOG.log(System.Logger.Level.DEBUG,
                    "Mansart pool extension idle: " + P_URL + " not set");
            return;
        }
        PoolConfig.Builder b = PoolConfig.builder().jdbcUrl(url.get());
        vidocqConfig.property(P_USERNAME).ifPresent(b::username);
        vidocqConfig.property(P_PASSWORD).ifPresent(b::password);
        vidocqConfig.property(P_MIN_IDLE).map(Integer::parseInt).ifPresent(b::minIdle);
        vidocqConfig.property(P_MAX_SIZE).map(Integer::parseInt).ifPresent(b::maxSize);
        vidocqConfig.property(P_ACQUIRE_TIMEOUT).map(Duration::parse).ifPresent(b::acquireTimeout);
        vidocqConfig.property(P_IDLE_TIMEOUT).map(Duration::parse).ifPresent(b::idleTimeout);
        vidocqConfig.property(P_MAX_LIFETIME).map(Duration::parse).ifPresent(b::maxLifetime);
        vidocqConfig.property(P_VALIDATION_TIMEOUT).map(Duration::parse).ifPresent(b::validationTimeout);
        vidocqConfig.property(P_VALIDATION).map(s -> ValidationMode.valueOf(s.trim().toUpperCase()))
                .ifPresent(b::validation);
        vidocqConfig.property(P_VALIDATION_QUERY).ifPresent(b::validationQuery);
        vidocqConfig.property(P_LEAK_DETECTION_THRESHOLD).map(Duration::parse)
                .ifPresent(b::leakDetectionThreshold);
        this.poolConfig = b.build();
        LOG.log(System.Logger.Level.INFO,
                "Mansart pool configured: url=" + url.get()
                        + " maxSize=" + poolConfig.maxSize()
                        + " validation=" + poolConfig.validation());
    }

    @Override
    public void beforeStart(VaubanContainerBuilder builder) {
        if (poolConfig == null) return;
        this.pool = MansartDataSource.of(poolConfig);
        // Publish to the holder BEFORE adding it as a bean class so the @Produces method has
        // its singleton ready by the time Vauban's bean discovery enumerates the producer.
        MansartPoolHolder.INSTANCE = this.pool;
        builder.addBeanClass(MansartPoolHolder.class);
    }

    @Override
    public void onStop() {
        if (pool == null) return;
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

    /** Visible for tests. */
    PoolConfig poolConfig() {
        return poolConfig;
    }

    /** Visible for tests. */
    MansartDataSource pool() {
        return pool;
    }
}
