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
package io.vidocq.runtime.extensions.essentials.migration.flyway;

import io.vidocq.runtime.extensions.essentials.migration.MigrationExtension;
import io.vidocq.runtime.spi.ExtensionContext;
import io.vidocq.runtime.spi.VidocqConfiguration;
import io.vidocq.runtime.spi.config.VidocqConfig;
import io.vidocq.runtime.spi.devconsole.LivePanel;
import io.vidocq.runtime.spi.devconsole.PanelAction;
import io.vidocq.runtime.spi.report.LaunchMode;
import io.vidocq.vauban.core.container.VaubanContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code migration} panel of the dev console on Flyway and an in-memory H2 database: the extension and its
 * {@code -dev} companion's live panel, each found as Vidocq finds them, through the {@link ServiceLoader}
 * (vidocq#120, Vidocq/vidocq#143).
 */
class MigrationPanelH2Test {

    private static final String PASSWORD = "s3cr3t-pw";

    @TempDir
    Path dir;

    /** Found by id, exactly as the dev console finds it: no direct dependency on the -dev module's own package. */
    private final LivePanel panel = ServiceLoader.load(LivePanel.class, MigrationPanelH2Test.class.getClassLoader())
            .stream().map(ServiceLoader.Provider::get)
            .filter(p -> "migration".equals(p.id())).findFirst().orElseThrow();

    private MigrationExtension ext;

    /** The panel reads the static holder the runtime extension publishes: never leak one test into the next. */
    @AfterEach
    void stop() {
        if (ext != null) {
            ext.onStop();
        }
    }

    @Test
    void migrateAppliesAMigrationAddedAfterTheBoot() throws Exception {
        Path scripts = scripts();
        String url = url();
        booted(url, scripts, Map.of());
        Files.writeString(scripts.resolve("V2__create_gizmo.sql"), "CREATE TABLE gizmo (id INT PRIMARY KEY);");

        String result = run("migrate");

        assertEquals("default: 1 migration applied, schema at version 2", result);
        assertEquals(0, count(url, "gizmo"));
        RecordingSample group = sampled().groups().get("default");
        assertEquals("2", group.text("version"));
        assertEquals("migrate: 1 applied", group.text("last-run"));
        assertTrue(group.text("applied").contains("create gizmo"), group.text("applied"));
        assertEquals("[]", group.text("pending"));
    }

    @Test
    void cleanAndMigrateIsRefusedByDefaultAndDropsNothing() throws Exception {
        String url = url();
        booted(url, scripts(), Map.of());
        execute(url, "INSERT INTO widget VALUES (1)");

        String result = run("clean-and-migrate");

        assertEquals("default: clean refused, nothing dropped; set vidocq.migration.cleanDisabled=false to allow it",
                result);
        assertEquals(1, count(url, "widget"), "the row is still there");
    }

    @Test
    void cleanAndMigrateWithTheKeyRecreatesTheSchema() throws Exception {
        String url = url();
        booted(url, scripts(), Map.of("vidocq.migration.cleanDisabled", "false"));
        execute(url, "INSERT INTO widget VALUES (1)");

        String result = run("clean-and-migrate");

        assertEquals("default: schema cleaned, 1 migration applied, schema at version 1", result);
        assertEquals(0, count(url, "widget"), "dropped, then created again");
        assertEquals("clean-and-migrate: 1 applied", sampled().groups().get("default").text("last-run"));
    }

    @Test
    void thePasswordReachesNeitherThePageNorTheResults() throws Exception {
        String url = url();
        booted(url, scripts(), Map.of("vidocq.migration.cleanDisabled", "false"));

        String results = run("migrate") + run("clean-and-migrate");
        String sample = sampled().toString();

        assertTrue(sample.contains("create widget"), sample);
        assertFalse(sample.contains(PASSWORD), sample);
        assertFalse(results.contains(PASSWORD), results);
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private Path scripts() throws Exception {
        Path scripts = Files.createDirectories(dir.resolve("migrations"));
        Files.writeString(scripts.resolve("V1__create_widget.sql"), "CREATE TABLE widget (id INT PRIMARY KEY);");
        return scripts;
    }

    /** The extension through a boot: configured, migrated, started in a dev launch — publishes the panel's control. */
    private void booted(String url, Path scripts, Map<String, String> more) {
        Map<String, String> config = new HashMap<>(Map.of(
                "vidocq.pool.url", url,
                "vidocq.pool.username", "sa",
                "vidocq.pool.password", PASSWORD,
                "vidocq.migration.locations", "filesystem:" + scripts));
        config.putAll(more);
        ext = new MigrationExtension();
        ext.configure(new MapConfig(config));
        ext.beforeStart(null);
        ext.onStart(new DevContext());
    }

    private String run(String action) {
        PanelAction found = panel.actions().stream().filter(a -> a.id().equals(action)).findFirst().orElseThrow();
        return found.run().apply(Map.of("datasource", "default"));
    }

    private RecordingSample sampled() {
        RecordingSample sample = new RecordingSample();
        panel.sample(sample);
        return sample;
    }

    private static String url() {
        return "jdbc:h2:mem:panel-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
    }

    private static void execute(String url, String sql) throws Exception {
        try (Connection c = DriverManager.getConnection(url, "sa", PASSWORD); var s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static int count(String url, String table) throws Exception {
        try (Connection c = DriverManager.getConnection(url, "sa", PASSWORD);
             var s = c.createStatement();
             var rs = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
            assertTrue(rs.next());
            return rs.getInt(1);
        }
    }

    private record MapConfig(Map<String, String> data) implements VidocqConfiguration {
        @Override public Optional<String> property(String key) {
            return Optional.ofNullable(data.get(key));
        }
        @Override public Iterable<String> propertyNames() {
            return data.keySet();
        }
    }

    private record DevContext() implements ExtensionContext {
        @Override public VaubanContainer container() { return null; }
        @Override public VidocqConfiguration configuration() { return null; }
        @Override public VidocqConfig config() { return null; }
        @Override public LaunchMode launchMode() { return LaunchMode.DEV; }
    }
}
