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
package io.vidocq.runtime.cli.ext;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtensionRegistryTest {

    private static final String REMOTE_JSON = """
            [
              {"id": "knock-health", "groupId": "io.vidocq.x", "artifactId": "a", "description": "Health"},
              {"id": "dirac-metrics", "groupId": "io.vidocq.x", "artifactId": "b", "description": "Metrics"}
            ]
            """;

    private static final List<RegistryEntry> CATALOG = List.of(
            new RegistryEntry("fallback", "io.vidocq", "vidocq-runtime-fallback-extension", "Offline"));

    @Test
    void remoteSuccessParsesAndWritesCache(@TempDir Path dir) {
        Path cache = dir.resolve("registry-cache.json");
        var registry = new ExtensionRegistry(() -> Optional.of(REMOTE_JSON), cache, CATALOG);

        var listing = registry.list();
        assertEquals(ExtensionRegistry.Origin.REMOTE, listing.origin());
        assertEquals(2, listing.entries().size());
        assertEquals("knock-health", listing.entries().get(0).id());
        assertTrue(Files.exists(cache), "remote body should be cached");
    }

    @Test
    void fallsBackToCacheWhenOffline(@TempDir Path dir) throws Exception {
        Path cache = dir.resolve("registry-cache.json");
        Files.writeString(cache, REMOTE_JSON);
        var registry = new ExtensionRegistry(Optional::empty, cache, CATALOG);

        var listing = registry.list();
        assertEquals(ExtensionRegistry.Origin.CACHE, listing.origin());
        assertEquals(2, listing.entries().size());
    }

    @Test
    void fallsBackToCatalogWhenOfflineAndNoCache(@TempDir Path dir) {
        Path cache = dir.resolve("missing.json");
        var registry = new ExtensionRegistry(Optional::empty, cache, CATALOG);

        var listing = registry.list();
        assertEquals(ExtensionRegistry.Origin.CATALOG, listing.origin());
        assertEquals(1, listing.entries().size());
        assertEquals("fallback", listing.entries().get(0).id());
        assertFalse(Files.exists(cache));
    }

    @Test
    void fetcherExceptionDegradesGracefully(@TempDir Path dir) {
        var registry = new ExtensionRegistry(() -> { throw new RuntimeException("boom"); },
                dir.resolve("nope.json"), CATALOG);
        assertEquals(ExtensionRegistry.Origin.CATALOG, registry.list().origin());
    }

    @Test
    void malformedRemoteFallsThroughToCatalog(@TempDir Path dir) {
        var registry = new ExtensionRegistry(() -> Optional.of("not json"),
                dir.resolve("nope.json"), CATALOG);
        assertEquals(ExtensionRegistry.Origin.CATALOG, registry.list().origin());
    }

    @Test
    void parseSkipsEntriesMissingRequiredFields() {
        String json = """
                [
                  {"id": "ok", "groupId": "g", "artifactId": "a"},
                  {"id": "bad-no-artifact", "groupId": "g"},
                  {"groupId": "g", "artifactId": "a"}
                ]
                """;
        List<RegistryEntry> entries = ExtensionRegistry.parse(json);
        assertEquals(1, entries.size());
        assertEquals("ok", entries.get(0).id());
    }
}
