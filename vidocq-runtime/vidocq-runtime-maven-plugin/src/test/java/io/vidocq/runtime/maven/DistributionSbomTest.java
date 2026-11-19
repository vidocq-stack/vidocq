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
package io.vidocq.runtime.maven;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DistributionSbomTest {

    @TempDir
    Path dir;

    @Test
    void writesACycloneDxDocumentWithOneComponentPerShippedJar() throws Exception {
        Path jar = dir.resolve("a.jar");
        Files.writeString(jar, "abc");
        Path out = dir.resolve("sbom/app-1.0-cyclonedx.json");

        DistributionSbom.write(out, new DistributionSbom.Subject("com.acme", "app", "1.0"),
                List.of(new DistributionSbom.Component("jakarta.inject", "jakarta.inject-api", "2.0.1", jar)));

        String json = Files.readString(out, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"bomFormat\": \"CycloneDX\""), json);
        assertTrue(json.contains("\"specVersion\": \"1.6\""), json);
        assertTrue(json.contains("\"purl\": \"pkg:maven/com.acme/app@1.0\""), json);
        assertTrue(json.contains("\"purl\": \"pkg:maven/jakarta.inject/jakarta.inject-api@2.0.1\""), json);
        assertTrue(json.contains("\"bom-ref\": \"pkg:maven/jakarta.inject/jakarta.inject-api@2.0.1\""), json);
        // SHA-256 of "abc", the digest of the file that ships.
        assertTrue(json.contains("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"), json);
    }

    @Test
    void isDeterministicAndEscapesStrings() throws Exception {
        Path jar = dir.resolve("a.jar");
        Files.writeString(jar, "x");
        var subject = new DistributionSbom.Subject("g", "we\"ird\\name", "1");
        var components = List.of(new DistributionSbom.Component("g", "a", "1", jar));
        Path one = dir.resolve("one.json");
        Path two = dir.resolve("two.json");

        DistributionSbom.write(one, subject, components);
        DistributionSbom.write(two, subject, components);

        assertEquals(Files.readString(one), Files.readString(two));
        assertTrue(Files.readString(one).contains("we\\\"ird\\\\name"));
        assertFalse(Files.readString(one).contains("timestamp"), "no clock in the file: the build stays reproducible");
    }
}
