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

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScanSelectionTest {

    @TempDir
    Path tmp;

    Path jar(String name, Map<String, String> entries, String scanPatterns) throws Exception {
        Path file = tmp.resolve(name);
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (scanPatterns != null) {
            manifest.getMainAttributes().putValue(ScanSelection.MANIFEST_ATTRIBUTE, scanPatterns);
        }
        try (var out = new JarOutputStream(Files.newOutputStream(file), manifest)) {
            for (var e : entries.entrySet()) {
                out.putNextEntry(new JarEntry(e.getKey()));
                out.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return file;
    }

    ScanSelection.Dependency dep(String g, String a, Path jar) {
        return new ScanSelection.Dependency(g, a, "1.0", jar);
    }

    static Map<String, ScanSelection.Decision> byArtifact(List<ScanSelection.Decision> decisions) {
        Map<String, ScanSelection.Decision> map = new LinkedHashMap<>();
        decisions.forEach(d -> map.put(d.dependency().artifactId(), d));
        return map;
    }

    static Artifact artifact(String artifactId, String type, Path file) {
        Artifact a = new DefaultArtifact("org.x", artifactId, "1.0", "compile", type, null,
                new DefaultArtifactHandler(type));
        a.setFile(file.toFile());
        return a;
    }

    @Test
    void onlyJarsAndDirectoriesAreReadSoAPomOrZipDependencyBreaksNothing() throws Exception {
        Path pom = Files.writeString(tmp.resolve("bom-1.0.pom"), "<project/>");
        Path zip = Files.writeString(tmp.resolve("dist-1.0.zip"), "not an archive");
        Path lib = jar("lib-1.0.jar", Map.of("META-INF/beans.xml", ""), null);
        Path classes = Files.createDirectories(tmp.resolve("reactor/target/classes"));

        List<ScanSelection.Dependency> deps = ScanSelection.dependenciesOf(List.of(artifact("bom", "pom", pom),
                artifact("dist", "zip", zip), artifact("lib", "jar", lib), artifact("mod", "jar", classes)));

        assertEquals(List.of("lib", "mod"), deps.stream().map(ScanSelection.Dependency::artifactId).toList());
        assertEquals(2, ScanSelection.decide(deps, List.of("org.x:*"), List.of(), true).size());
    }

    @Test
    void aBeanArchiveIsScannedAutomaticallyUnlessItsDiscoveryModeIsNone() throws Exception {
        var annotated = dep("org.a", "annotated", jar("a.jar", Map.of("META-INF/beans.xml", ""), null));
        var none = dep("org.n", "none", jar("n.jar",
                Map.of("META-INF/beans.xml", "<beans bean-discovery-mode=\"none\"/>"), null));
        var plain = dep("org.p", "plain", jar("p.jar", Map.of("org/p/P.class", "x"), null));

        var d = byArtifact(ScanSelection.decide(List.of(annotated, none, plain), List.of(), List.of(), true));

        assertTrue(d.get("annotated").selected());
        assertEquals(ScanSelection.Source.AUTOMATIC, d.get("annotated").source());
        assertEquals("annotated", d.get("annotated").facts().discoveryMode());
        assertFalse(d.get("none").selected());
        assertFalse(d.get("plain").selected());
        assertFalse(byArtifact(ScanSelection.decide(List.of(annotated), List.of(), List.of(), false))
                .get("annotated").selected(), "autoScan=false turns the detection off");
    }

    @Test
    void anExtensionManifestSelectsTheJarsItNames() throws Exception {
        var extension = dep("io.vidocq", "ext", jar("ext.jar", Map.of(), "org.lib:*"));
        var lib = dep("org.lib", "lib", jar("lib.jar", Map.of("org/lib/L.class", "x"), null));

        var d = byArtifact(ScanSelection.decide(List.of(extension, lib), List.of(), List.of(), false));

        assertTrue(d.get("lib").selected());
        assertEquals(ScanSelection.Source.EXTENSION, d.get("lib").source());
        assertFalse(d.get("ext").selected());
    }

    @Test
    void processedJarsAndExcludesAreLeftOutWithTheirReason() throws Exception {
        var processed = dep("io.vidocq", "brick", jar("b.jar", Map.of(
                "META-INF/beans.xml", "", "META-INF/vauban-bce-processed", ""), null));
        var excluded = dep("org.x", "excluded", jar("x.jar", Map.of("META-INF/beans.xml", ""), null));

        var d = byArtifact(ScanSelection.decide(List.of(processed, excluded), List.of(), List.of("org.x:*"), true));

        assertFalse(d.get("brick").selected());
        assertTrue(d.get("brick").excludedBecause().contains("already carries generated code"));
        assertFalse(d.get("excluded").selected());
        assertTrue(d.get("excluded").excludedBecause().contains("scanExcludes"));
    }

    @Test
    void aSignedJarIsSkippedUnlessTheApplicationNamesIt() throws Exception {
        var signed = dep("org.s", "signed", jar("s.jar", Map.of("META-INF/beans.xml", "",
                "META-INF/SIGNER.SF", "Signature-Version: 1.0\n"), null));

        var auto = byArtifact(ScanSelection.decide(List.of(signed), List.of(), List.of(), true)).get("signed");
        var named = byArtifact(ScanSelection.decide(List.of(signed), List.of("org.s:signed"), List.of(), true))
                .get("signed");

        assertFalse(auto.selected());
        assertTrue(auto.excludedBecause().contains("signed"));
        assertTrue(named.selected());
        assertEquals(ScanSelection.Source.APPLICATION, named.source());
    }

    @Test
    void aReactorDirectoryIsInspectedLikeAJar() throws Exception {
        Path dir = tmp.resolve("reactor/target/classes");
        Files.createDirectories(dir.resolve("META-INF"));
        Files.writeString(dir.resolve("META-INF/beans.xml"), "");

        var facts = ScanSelection.inspect(dir);

        assertTrue(facts.beansXml());
        assertTrue(facts.automatic());
        assertFalse(facts.signed());
        assertTrue(ScanSelection.decide(List.of(dep("org.r", "reactor", dir)), List.of(), List.of(), true)
                .getFirst().selected());
    }
}
