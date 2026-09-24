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
package io.vidocq.runtime.devservices.host;

import io.vidocq.runtime.devservices.spi.DevServiceState;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StateFileTest {

    @Test
    void secretsAreNeverWrittenAndUrlsLoseTheirCredentials() {
        Map<String, String> injected = new LinkedHashMap<>();
        injected.put("vidocq.pool.url", "jdbc:postgresql://h/db?user=u&password=p");
        injected.put("vidocq.pool.username", "vidocq");
        injected.put("vidocq.pool.password", "s3cret-value");
        DevServiceState pg = new DevServiceState("postgres", "postgres:16-alpine",
                Map.of("default", "localhost:54321"), List.copyOf(injected.keySet()));

        String json = StateFile.json("vidocq:dev", "running", Instant.parse("2026-09-24T10:12:03Z"),
                List.of(pg), injected);

        assertFalse(json.contains("s3cret-value"), json);
        assertFalse(json.contains("password=p"), json);
        assertTrue(json.contains("\"key\":\"vidocq.pool.password\",\"configured\":true"), json);
        assertTrue(json.contains("\"value\":\"jdbc:postgresql://h/db?user=u&password=***\""), json);
        assertTrue(json.contains("\"host\":\"vidocq:dev\""), json);
        assertTrue(json.contains("\"startedAt\":\"2026-09-24T10:12:03Z\""), json);
        assertTrue(json.contains("\"endpoints\":{\"default\":\"localhost:54321\"}"), json);
    }

    @Test
    void twoDatasourcesGiveBothEndpointsAndBothPasswordsConfigured() {
        Map<String, String> injected = new LinkedHashMap<>();
        for (String p : List.of("vidocq.pool.", "vidocq.pool.audit.")) {
            injected.put(p + "url", "jdbc:postgresql://localhost:5/x");
            injected.put(p + "username", "u");
            injected.put(p + "password", "pw");
        }
        DevServiceState pg = new DevServiceState("postgres", "img",
                Map.of("default", "localhost:5", "audit", "localhost:6"), List.copyOf(injected.keySet()));
        String json = StateFile.json("test", "running", Instant.EPOCH, List.of(pg), injected);
        assertEquals(2, json.split("\"configured\":true", -1).length - 1, json);
        assertFalse(json.contains("\"pw\""), json);
    }

    @Test
    void endpointsLoseTheirUserInfoAndSecretQueryParameters() {
        Map<String, String> endpoints = new LinkedHashMap<>();
        endpoints.put("admin", "http://admin:hunter2@localhost:8180/admin");
        endpoints.put("token", "http://localhost:8180/realms/vidocq?client_id=app&access_token=abc123&x=1");
        DevServiceState kc = new DevServiceState("keycloak", "quay.io/keycloak/keycloak:26", endpoints, List.of());

        String json = StateFile.json("vidocq:dev", "running", Instant.EPOCH, List.of(kc), Map.of());

        assertFalse(json.contains("hunter2"), json);
        assertFalse(json.contains("abc123"), json);
        assertTrue(json.contains("\"admin\":\"http://***@localhost:8180/admin\""), json);
        assertTrue(json.contains("?client_id=app&access_token=***&x=1"), json);
    }

    @Test
    void writeIsAtomic(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("target").resolve(StateFile.FILE_NAME);
        StateFile.write(file, "{\"a\":1}");
        assertEquals("{\"a\":1}", Files.readString(file));
        try (var s = Files.list(file.getParent())) {
            assertEquals(1, s.count(), "no temporary file left behind");
        }
    }

    @Test
    void jsonEscapesQuotesAndControlCharacters() {
        DevServiceState s = new DevServiceState("x", "img\"\n", Map.of(), List.of());
        String json = StateFile.json("t", "running", Instant.EPOCH, List.of(s), Map.of());
        assertTrue(json.contains("\"image\":\"img\\\"\\n\""), json);
    }
}
