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
package io.vidocq.runtime.extensions.essentials.devconsole;

import io.vidocq.runtime.core.config.ConfigKeyAudit;
import io.vidocq.runtime.core.config.VidocqConfigImpl;
import io.vidocq.runtime.spi.config.ConfigSource;
import io.vidocq.runtime.spi.config.VidocqConfig;
import io.vidocq.runtime.spi.report.LaunchMode;
import io.vidocq.runtime.spi.report.ReportAnomaly;
import io.vidocq.runtime.spi.report.ReportLine;
import io.vidocq.runtime.spi.report.ReportSection;
import io.vidocq.runtime.spi.report.StartupReportView;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The console's own {@code config} panel: the keys the application sets, the source each value comes from, its value
 * in a dev launch only and never a secret, and whether the core or an extension reads it.
 */
class ConfigPanelTest {

    private static final String SECRET = "the-request-state-secret-never-shown";
    private static final String DB_PASSWORD = "db-password-never-shown";

    /** A source of fixed keys, as a properties file, the system properties or the environment give them. */
    private record Source(String name, int ordinal, Map<String, String> values) implements ConfigSource {

        @Override
        public String getName() {
            return name;
        }

        @Override
        public int getOrdinal() {
            return ordinal;
        }

        @Override
        public String getValue(String key) {
            return values.get(key);
        }

        @Override
        public Set<String> getPropertyNames() {
            return values.keySet();
        }
    }

    /**
     * The environment as MicroProfile Config reads it: {@code DB_USER} is found for {@code db.user}, yet only
     * {@code DB_USER} is a property name.
     */
    private record Environment(Map<String, String> variables) implements ConfigSource {

        @Override
        public String getName() {
            return "EnvironmentVariablesConfigSource";
        }

        @Override
        public int getOrdinal() {
            return 300;
        }

        @Override
        public String getValue(String key) {
            String exact = variables.get(key);
            return exact != null ? exact : variables.get(key.replaceAll("[^A-Za-z0-9]", "_").toUpperCase());
        }

        @Override
        public Set<String> getPropertyNames() {
            return variables.keySet();
        }
    }

    /**
     * The configuration of these tests: a properties file, the system properties, which override {@code db.url} and
     * add the request-state secret, and the environment, which overrides {@code db.user}.
     */
    private static VidocqConfig config() {
        Map<String, String> file = new LinkedHashMap<>();
        file.put("app.name", "orders");
        file.put("db.url", "jdbc:postgresql://file:filepw@localhost/orders");
        file.put("db.user", "file-user");
        file.put("db.adminPassword", DB_PASSWORD);
        file.put("vidocq.http.port", "8081");
        file.put("vidocq.http.prot", "8082");
        file.put("mp.config.profile", "dev");
        Map<String, String> system = new LinkedHashMap<>();
        system.put("java.version", "25");
        system.put("db.url", "jdbc:postgresql://admin:s3cr3t@db/orders?password=0th3rpw");
        system.put("vidocq.mcp.requestStateSecret", SECRET);
        return new VidocqConfigImpl(List.of(new Source("PropertiesFile", 100, file),
                new Source("SystemProperties", 400, system),
                new Environment(Map.of("PATH", "/usr/bin", "DB_USER", "env-user"))));
    }

    private static ConfigPanel panel(LaunchMode mode, Supplier<Optional<StartupReportView>> report) {
        return ConfigPanel.of(config(), mode, report);
    }

    private static ConfigPanel devPanel() {
        return panel(LaunchMode.DEV, Optional::empty);
    }

    /** The report of a boot whose audit claims {@code vidocq.http.*} and found {@code vidocq.http.prot} unread. */
    private static StartupReportView report() {
        Set<String> declared = Set.of("vidocq.http.port", "vidocq.mcp.*");
        return new FakeReportView(LaunchMode.DEV, "vidocq.launch.mode",
                List.of(new ReportAnomaly("VIDOCQ-CFG-003", ConfigKeyAudit.messageFor("vidocq.http.prot", declared),
                        null, "core"),
                        new ReportAnomaly("VIDOCQ-DEVC-001", "Configuration key 'app.name' is read by nothing", null,
                                "devconsole")),
                List.of(new ReportSection("configuration", null, null, List.of(
                        new ReportLine("sources", List.of("SystemProperties 400", "PropertiesFile 100")),
                        new ReportLine("audited", ConfigKeyAudit.auditedNamespaces(declared))))),
                "", List.of());
    }

    @Test
    void itIsTheConsolesOwnPanel() {
        ConfigPanel panel = devPanel();

        assertEquals("config", panel.id());
        assertEquals("Configuration", panel.title());
        PanelEntry entry = PanelEntry.builtIn(panel, LaunchMode.DEV);
        assertEquals("config", entry.id());
        assertEquals(panel, entry.panel(), "live: its table is written by sample()");
        assertEquals(List.of(), entry.charts(), "nothing to plot: the configuration does not move");
    }

    @Test
    void theTableListsTheApplicationsKeysThenVidocqThenMpWithTheSourceThatWins() {
        List<List<String>> rows = rows(table(sampled(devPanel()), "keys"));

        assertEquals(List.of(
                List.of("app.name", "PropertiesFile", "100", "orders", "report not written yet"),
                List.of("db.adminPassword", "PropertiesFile", "100", "configured", "report not written yet"),
                List.of("db.url", "SystemProperties", "400", "jdbc:postgresql://***@db/orders?password=***",
                        "report not written yet"),
                List.of("db.user", "EnvironmentVariablesConfigSource", "300", "env-user", "report not written yet"),
                List.of("vidocq.http.port", "PropertiesFile", "100", "8081", "report not written yet"),
                List.of("vidocq.http.prot", "PropertiesFile", "100", "8082", "report not written yet"),
                List.of("vidocq.mcp.requestStateSecret", "SystemProperties", "400", "configured",
                        "report not written yet"),
                List.of("mp.config.profile", "PropertiesFile", "100", "dev", "report not written yet")), rows);
    }

    @Test
    void theWinningSourceIsTheOneWhoseValueGetValueReturns() {
        VidocqConfig config = config();
        List<ConfigSource> sources = new java.util.ArrayList<>();
        config.getConfigSources().forEach(sources::add);

        for (List<String> row : rows(table(sampled(devPanel()), "keys"))) {
            String key = row.get(0);
            ConfigSource winner = sources.stream().filter(source -> source.getName().equals(row.get(1)))
                    .findFirst().orElseThrow();
            assertEquals(config.getValue(key), Optional.ofNullable(winner.getValue(key)), key);
        }
    }

    @Test
    void theColumnsNameWhatTheRowsHold() {
        assertEquals(List.of("key", "source", "ordinal", "value", "status"),
                table(sampled(devPanel()), "keys").get("columns"));
    }

    @Test
    void theSystemPropertiesAndTheEnvironmentShowOnlyWhereTheyOverrideTheApplication() {
        String json = sampled(devPanel()).toString();

        assertFalse(json.contains("java.version"), json);
        assertFalse(json.contains("PATH"), json);
    }

    @Test
    void noSecretReachesTheTableNorTheBootFacts() {
        String sample = sampled(devPanel()).toString();
        String facts = contributed(devPanel()).toString();

        for (String secret : List.of(SECRET, DB_PASSWORD, "s3cr3t", "0th3rpw", "filepw")) {
            assertFalse(sample.contains(secret), secret + " in " + sample);
            assertFalse(facts.contains(secret), secret + " in " + facts);
        }
    }

    @Test
    void outsideADevLaunchNoValueIsShown() {
        List<List<String>> rows = rows(table(sampled(panel(LaunchMode.TEST, Optional::empty)), "keys"));

        assertEquals(8, rows.size(), "the keys and their sources are still listed");
        assertTrue(rows.stream().allMatch(row -> row.get(3).equals("not shown outside dev")), rows.toString());
        assertEquals(List.of("not shown outside dev"),
                facts(contributed(panel(LaunchMode.PROD, Optional::empty))).get("values"));
    }

    @Test
    void theStatusSaysWhichKeysTheAuditFoundUnreadOnceTheReportIsWritten() {
        AtomicReference<StartupReportView> written = new AtomicReference<>();
        ConfigPanel panel = panel(LaunchMode.DEV, () -> Optional.ofNullable(written.get()));
        assertEquals("report not written yet", status(panel, "vidocq.http.prot"));

        written.set(report());

        assertEquals("unused", status(panel, "vidocq.http.prot"), "named by a VIDOCQ-CFG-003 anomaly");
        assertEquals("claimed", status(panel, "vidocq.http.port"), "under a namespace the audit covers");
        assertEquals("claimed", status(panel, "vidocq.mcp.requestStateSecret"));
        assertEquals("not audited", status(panel, "app.name"), "another code quoting the key is not the audit");
        assertEquals("not audited", status(panel, "mp.config.profile"));
    }

    @Test
    void theStatusIsReadFromTheReportOnceAndKept() {
        AtomicReference<StartupReportView> written = new AtomicReference<>(report());
        ConfigPanel panel = panel(LaunchMode.DEV, () -> Optional.ofNullable(written.get()));
        Map<String, Object> first = table(sampled(panel), "keys");

        written.set(null);

        assertEquals(first, table(sampled(panel), "keys"), "a report that is gone does not change the rows");
    }

    @Test
    void theBootFactsCountTheKeysAndListTheSourcesInLookupOrder() {
        ReportSection section = contributed(devPanel());

        assertEquals("8 keys: 4 of the application, 3 vidocq.*, 1 mp.*; 3 sources", section.summary());
        Map<String, List<String>> facts = facts(section);
        assertEquals(List.of("SystemProperties 400", "EnvironmentVariablesConfigSource 300", "PropertiesFile 100"),
                facts.get("sources"));
        assertEquals(List.of("shown, a secret as configured and a URL without its credentials"),
                facts.get("values"));
        assertFalse(facts.containsKey("keys left out"), "nothing left out of eight rows");
    }

    @Test
    void theTableShowsAHundredRowsTheApplicationsFirstAndTheBootFactsSayHowManyWereLeftOut() {
        Map<String, String> file = new LinkedHashMap<>();
        for (int i = 0; i < 90; i++) {
            file.put("vidocq.k" + (100 + i), "v");
            file.put("app.k" + (100 + i), "v");
        }
        ConfigPanel panel = ConfigPanel.of(new VidocqConfigImpl(List.of(new Source("PropertiesFile", 100, file))),
                LaunchMode.DEV, Optional::empty);

        List<List<String>> rows = rows(table(sampled(panel), "keys"));
        assertEquals(100, rows.size());
        assertTrue(rows.subList(0, 90).stream().allMatch(row -> row.get(0).startsWith("app.")), "application first");
        assertEquals(List.of("80, past the 100 rows the table shows"), facts(contributed(panel)).get("keys left out"));
    }

    @Test
    void withoutAConfigurationThePanelSaysSoAndWritesNoTable() {
        ConfigPanel panel = ConfigPanel.of(null, LaunchMode.DEV, Optional::empty);

        assertEquals("not available: no configuration", contributed(panel).summary());
        assertNull(table(sampled(panel), "keys"));
    }

    @Test
    void noKeyIsWrittenAbsent() {
        ConfigPanel panel = ConfigPanel.of(new VidocqConfigImpl(List.of()), LaunchMode.DEV, Optional::empty);

        assertEquals(Map.of("key", "keys", "kind", "absent", "reason", "none"), table(sampled(panel), "keys"));
        assertEquals("0 keys: 0 of the application, 0 vidocq.*, 0 mp.*; 0 sources", contributed(panel).summary());
    }

    @Test
    void aSourceThatFailsCostsItsKeysNotThePanel() {
        ConfigSource failing = new ConfigSource() {
            @Override
            public String getName() {
                return "Failing";
            }

            @Override
            public int getOrdinal() {
                return 500;
            }

            @Override
            public String getValue(String key) {
                throw new IllegalStateException("unreadable");
            }

            @Override
            public Set<String> getPropertyNames() {
                throw new IllegalStateException("unreadable");
            }
        };
        ConfigPanel panel = ConfigPanel.of(new VidocqConfigImpl(List.of(failing,
                new Source("PropertiesFile", 100, Map.of("app.name", "orders")))), LaunchMode.DEV, Optional::empty);

        assertEquals(List.of(List.of("app.name", "PropertiesFile", "100", "orders", "report not written yet")),
                rows(table(sampled(panel), "keys")));
        assertEquals(List.of("Failing 500", "PropertiesFile 100"), facts(contributed(panel)).get("sources"));
    }

    private static String status(ConfigPanel panel, String key) {
        return rows(table(sampled(panel), "keys")).stream().filter(row -> row.get(0).equals(key)).findFirst()
                .orElseThrow().get(4);
    }

    private static Map<String, List<String>> facts(ReportSection section) {
        Map<String, List<String>> byKey = new LinkedHashMap<>();
        section.lines().forEach(line -> byKey.put(line.key(), line.values()));
        return byKey;
    }

    private static ReportSection contributed(ConfigPanel panel) {
        RecordingSection section = new RecordingSection(ConfigPanel.ID, panel.title());
        panel.contribute(new ConsoleReportContext(LaunchMode.DEV), section);
        return section.toSection();
    }

    private static Map<String, Object> sampled(ConfigPanel panel) {
        RecordingSample sample = new RecordingSample();
        panel.sample(sample);
        JsonWriter out = new JsonWriter().beginObject();
        sample.writeTo(out);
        return Json.object(out.endObject().toString());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> table(Map<String, Object> sample, String key) {
        return ((List<Map<String, Object>>) sample.get("values")).stream().filter(v -> key.equals(v.get("key")))
                .findFirst().orElse(null);
    }

    @SuppressWarnings("unchecked")
    private static List<List<String>> rows(Map<String, Object> table) {
        return (List<List<String>>) table.get("rows");
    }
}
