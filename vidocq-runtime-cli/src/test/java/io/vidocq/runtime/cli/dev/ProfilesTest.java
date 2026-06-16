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
package io.vidocq.runtime.cli.dev;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfilesTest {

    @Test
    void profileFileNameFollowsConvention() {
        assertEquals("vidocq-prod.properties", Profiles.profileFileName("prod"));
    }

    @Test
    void mergeLetsProfileWinAndKeepsInputsUntouched() {
        Map<String, String> base = Map.of("a", "1", "b", "2");
        Map<String, String> profile = Map.of("b", "20", "c", "3");

        Map<String, String> merged = Profiles.merge(base, profile);

        assertEquals("1", merged.get("a"));   // base only
        assertEquals("20", merged.get("b"));  // profile wins
        assertEquals("3", merged.get("c"));   // profile only
        assertEquals(2, base.size());         // not mutated
        assertEquals(2, profile.size());
    }

    @Test
    void sourceFilesReturnsBaseThenProfileWhenBothExist(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("vidocq.properties"), "x=1");
        Files.writeString(dir.resolve("vidocq-dev.properties"), "x=2");

        List<Path> files = Profiles.sourceFiles(dir, "dev");

        assertEquals(2, files.size());
        assertEquals("vidocq.properties", files.get(0).getFileName().toString());
        assertEquals("vidocq-dev.properties", files.get(1).getFileName().toString());
    }

    @Test
    void sourceFilesSkipsMissingProfileFile(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("vidocq.properties"), "x=1");

        List<Path> files = Profiles.sourceFiles(dir, "prod");

        assertEquals(1, files.size());
        assertEquals("vidocq.properties", files.get(0).getFileName().toString());
    }

    @Test
    void sourceFilesEmptyWhenNothingExists(@TempDir Path dir) {
        assertTrue(Profiles.sourceFiles(dir, "dev").isEmpty());
        assertTrue(Profiles.sourceFiles(dir, null).isEmpty());
    }

    @Test
    void loadMergesInOrderWithLaterFilesWinning(@TempDir Path dir) throws IOException {
        Path base = dir.resolve("vidocq.properties");
        Path profile = dir.resolve("vidocq-dev.properties");
        Files.writeString(base, "port=8080\nname=base");
        Files.writeString(profile, "port=9090");

        Map<String, String> effective = Profiles.load(List.of(base, profile));

        assertEquals("9090", effective.get("port")); // profile overrides
        assertEquals("base", effective.get("name")); // base preserved
    }

    @Test
    void mergeWithEmptyProfileReturnsBase() {
        Map<String, String> base = Map.of("a", "1");
        assertEquals(base, Profiles.merge(base, Map.of()));
        assertFalse(Profiles.merge(base, Map.of()).isEmpty());
    }
}
