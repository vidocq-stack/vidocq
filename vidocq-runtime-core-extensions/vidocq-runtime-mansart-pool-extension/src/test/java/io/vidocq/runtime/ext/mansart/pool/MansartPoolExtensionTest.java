package io.vidocq.runtime.ext.mansart.pool;

import io.vidocq.mansart.pool.PoolConfig;
import io.vidocq.mansart.pool.ValidationMode;
import io.vidocq.runtime.spi.VidocqConfiguration;
import io.vidocq.vauban.core.container.VaubanContainerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * VID-1 — exercises the lifecycle of {@link MansartPoolExtension} in isolation:
 * configure → beforeStart → onStop. The Vauban container is not booted; we just verify the
 * extension reads its config correctly, opens / closes the pool, and registers the
 * {@code DataSource} factory exactly once.
 */
class MansartPoolExtensionTest {

    private MansartPoolExtension ext = new MansartPoolExtension();

    @AfterEach
    void cleanup() {
        if (ext != null) ext.onStop();
    }

    @Test
    void noUrl_extensionStaysIdle() {
        ext.configure(MapConfig.of(Map.of()));
        assertNull(ext.poolConfig(), "extension must stay idle without vidocq.pool.url");

        VaubanContainerBuilder builder = new VaubanContainerBuilder();
        assertDoesNotThrow(() -> ext.beforeStart(builder));
        assertNull(ext.pool(), "no pool must be opened when extension is idle");
    }

    @Test
    void minimalUrlBootsPoolAndRegistersFactory() throws Exception {
        ext.configure(MapConfig.of(Map.of(
                "vidocq.pool.url", "jdbc:h2:mem:vid-pool-min-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1",
                "vidocq.pool.username", "sa"
        )));
        PoolConfig cfg = ext.poolConfig();
        assertNotNull(cfg);
        assertEquals(10, cfg.maxSize(), "default maxSize honoured");
        assertEquals(Duration.ofSeconds(5), cfg.acquireTimeout());

        VaubanContainerBuilder builder = new VaubanContainerBuilder();
        ext.beforeStart(builder);
        assertNotNull(ext.pool(), "pool must be opened");

        // Round-trip a query through the registered DataSource to prove it actually works.
        try (Connection c = ext.pool().getConnection();
             var s = c.createStatement();
             var rs = s.executeQuery("SELECT 1")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
        }
    }

    @Test
    void allTypedPropertiesAreParsed() {
        ext.configure(MapConfig.of(Map.ofEntries(
                Map.entry("vidocq.pool.url",                    "jdbc:h2:mem:vid-pool-typed-" + UUID.randomUUID()),
                Map.entry("vidocq.pool.username",               "sa"),
                Map.entry("vidocq.pool.password",               "secret"),
                Map.entry("vidocq.pool.minIdle",                "2"),
                Map.entry("vidocq.pool.maxSize",                "20"),
                Map.entry("vidocq.pool.acquireTimeout",         "PT3S"),
                Map.entry("vidocq.pool.idleTimeout",            "PT2M"),
                Map.entry("vidocq.pool.maxLifetime",            "PT15M"),
                Map.entry("vidocq.pool.validationTimeout",      "PT500S"),
                Map.entry("vidocq.pool.validation",             "on_borrow"),
                Map.entry("vidocq.pool.validationQuery",        "SELECT 1"),
                Map.entry("vidocq.pool.leakDetectionThreshold", "PT30S")
        )));
        PoolConfig cfg = ext.poolConfig();
        assertEquals(2, cfg.minIdle());
        assertEquals(20, cfg.maxSize());
        assertEquals(Duration.ofSeconds(3), cfg.acquireTimeout());
        assertEquals(Duration.ofMinutes(2), cfg.idleTimeout());
        assertEquals(Duration.ofMinutes(15), cfg.maxLifetime());
        assertEquals(Duration.ofSeconds(500), cfg.validationTimeout());
        assertEquals(ValidationMode.ON_BORROW, cfg.validation());
        assertEquals("SELECT 1", cfg.validationQuery());
        assertEquals(Duration.ofSeconds(30), cfg.leakDetectionThreshold());
        assertEquals("secret", cfg.password());
    }

    @Test
    void invalidConfigSurfaced() {
        // maxSize < 1 → Builder.build() throws IllegalArgumentException
        assertThrows(IllegalArgumentException.class, () -> ext.configure(MapConfig.of(Map.of(
                "vidocq.pool.url", "jdbc:h2:mem:bad",
                "vidocq.pool.maxSize", "0"
        ))));
    }

    @Test
    void onStopIsIdempotent() {
        ext.configure(MapConfig.of(Map.of(
                "vidocq.pool.url", "jdbc:h2:mem:vid-pool-stop-" + UUID.randomUUID()
        )));
        ext.beforeStart(new VaubanContainerBuilder());
        assertNotNull(ext.pool());
        ext.onStop();
        assertNull(ext.pool(), "pool reference cleared after stop");
        assertDoesNotThrow(() -> ext.onStop(), "second onStop must be a no-op");
    }

    /** Test-only fake for the legacy {@link VidocqConfiguration} backed by a plain map. */
    private record MapConfig(Map<String, String> data) implements VidocqConfiguration {
        static MapConfig of(Map<String, String> data) { return new MapConfig(data); }
        @Override public Optional<String> property(String key) {
            return Optional.ofNullable(data.get(key));
        }
    }
}
