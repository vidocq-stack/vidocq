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
package io.vidocq.runtime.tooling;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BUG-20261031-09: the configuration an extension needs comes with it, once, as a commented block of
 * {@code vidocq.properties}, and never overwrites a key the application set.
 */
class ExtensionConfigTest {

    private static final String BASE = "# Vidocq application configuration\nvidocq.http.port=8080\n";

    @Test
    void telemetryGetsItsServiceNameTheSdkAndALocalCollector() {
        Map<String, String> entries = PropertiesText.entries(
                ExtensionConfig.append(BASE, List.of("humboldt-telemetry"), "library"));

        assertEquals("false", entries.get("otel.sdk.disabled"));
        assertEquals("library", entries.get("otel.service.name"));
        assertEquals("http://localhost:4318", entries.get("otel.exporter.otlp.endpoint"));
        assertEquals("http/protobuf", entries.get("otel.exporter.otlp.protocol"));
        assertEquals("8080", entries.get("vidocq.http.port"));
    }

    /**
     * The Keycloak dev service sets the verification keys itself: JWT writes them commented, for production, with the
     * realm file a dev realm goes in, so that no key it writes outranks or breaks the dev service.
     */
    @Test
    void jwtWritesHintsAndNoKeyTheDevServiceSets() {
        String text = ExtensionConfig.append(BASE, List.of("cervantes-jwt"), "library");

        Map<String, String> entries = PropertiesText.entries(text);
        assertFalse(entries.keySet().stream().anyMatch(k -> k.startsWith("mp.jwt.") || k.startsWith("vidocq.dev.")),
                text);
        assertTrue(text.contains("#mp.jwt.verify.issuer=https://keycloak.example.com/realms/library\n"), text);
        assertTrue(text.contains("#mp.jwt.verify.publickey.location="), text);
        assertTrue(text.contains("#vidocq.dev.keycloak.realm-import=src/main/resources/keycloak/library-realm.json\n"),
                text);
        assertTrue(text.contains("#vidocq.dev.keycloak.realm=library\n"), text);
    }

    @Test
    void aBlockIsWrittenOnce() {
        String once = ExtensionConfig.append(BASE, List.of("humboldt-telemetry"), "library");

        assertEquals(once, ExtensionConfig.append(once, List.of("humboldt-telemetry"), "library"));
    }

    @Test
    void aKeyTheApplicationSetIsNeverOverwritten() {
        String text = ExtensionConfig.append(BASE + "otel.service.name=books\n", List.of("humboldt-telemetry"),
                "library");

        assertEquals("books", PropertiesText.entries(text).get("otel.service.name"));
        assertEquals(1, text.split("otel.service.name=", -1).length - 1, text);
    }

    @Test
    void anExtensionWithoutConfigurationWritesNothing() {
        assertEquals(BASE, ExtensionConfig.append(BASE, List.of("cassini-rest"), "library"));
    }
}
