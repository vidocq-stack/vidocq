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
package io.vidocq.runtime.maven.dev;

import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevServicesReportTest {

    @Test
    void groupsDefaultAndNamedDatasourcesAndParsesHostPort() {
        Map<String, String> collected = new LinkedHashMap<>();
        collected.put("vidocq.pool.url", "jdbc:postgresql://localhost:5440/vidocq");
        collected.put("vidocq.pool.username", "vidocq");
        collected.put("vidocq.pool.password", "secret");
        collected.put("vidocq.pool.analytics.url", "jdbc:postgresql://localhost:5441/analytics");
        collected.put("vidocq.pool.analytics.username", "vidocq");
        collected.put("vidocq.pool.analytics.password", "secret");

        List<DevServicesReport.Coordinates> ds = DevServicesReport.datasources(collected);
        assertEquals(2, ds.size());
        assertEquals("default", ds.get(0).name(), "the @Default pool is listed first");
        assertEquals("localhost", ds.get(0).host());
        assertEquals("5440", ds.get(0).port());
        assertEquals("analytics", ds.get(1).name());
        assertEquals("5441", ds.get(1).port());
    }

    @Test
    void ignoresTuningKeysAndOtherProviders() {
        Map<String, String> collected = Map.of(
                "vidocq.pool.url", "jdbc:postgresql://h:1/db",
                "vidocq.pool.maxSize", "10",                 // @Default tuning, not a coordinate
                "vidocq.pool.analytics.maxSize", "5",        // named tuning, not a coordinate
                "vidocq.dev.keycloak.issuer", "http://localhost:8080");
        List<DevServicesReport.Coordinates> ds = DevServicesReport.datasources(collected);
        assertEquals(1, ds.size());
        assertEquals("default", ds.get(0).name());
    }

    @Test
    void consoleBlockIsGenericAndListsCoordinates() {
        List<String> lines = DevServicesReport.consoleLines(Map.of(
                "vidocq.pool.analytics.url", "jdbc:postgresql://localhost:5441/analytics",
                "vidocq.pool.analytics.username", "vidocq",
                "vidocq.pool.analytics.password", "secret"));
        String text = String.join("\n", lines);
        assertEquals("Connection information:", lines.get(0));
        assertFalse(text.toLowerCase().contains("datagrip"), "block must not be tool-specific");
        assertTrue(text.contains("Datasource: analytics"));
        assertTrue(text.contains("5441"));
        assertTrue(text.contains("Password : secret"));
    }

    @Test
    void fileContentIsParseableProperties() throws Exception {
        String content = DevServicesReport.fileContent(Map.of(
                "vidocq.pool.url", "jdbc:postgresql://localhost:5440/vidocq",
                "vidocq.pool.username", "vidocq",
                "vidocq.pool.password", "secret"));
        Properties p = new Properties();
        p.load(new StringReader(content));
        assertEquals("jdbc:postgresql://localhost:5440/vidocq", p.getProperty("datasource.default.url"));
        assertEquals("localhost", p.getProperty("datasource.default.host"));
        assertEquals("5440", p.getProperty("datasource.default.port"));
        assertEquals("secret", p.getProperty("datasource.default.password"));
    }

    @Test
    void emptyWhenNoPoolKeys() {
        assertTrue(DevServicesReport.consoleLines(Map.of("foo", "bar")).isEmpty());
        assertFalse(DevServicesReport.fileContent(Map.of("foo", "bar")).contains("datasource."));
    }
}
