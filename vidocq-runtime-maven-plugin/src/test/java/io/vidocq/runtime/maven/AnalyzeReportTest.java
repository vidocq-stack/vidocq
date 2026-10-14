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

import io.vidocq.runtime.maven.depgen.Brick;
import io.vidocq.runtime.maven.depgen.DependencyCodegen;
import io.vidocq.runtime.maven.depgen.DependencyCodegenCache;
import io.vidocq.runtime.maven.depgen.DependencyCodegenHints;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalyzeReportTest {

    @TempDir
    Path tmp;

    private static final Brick FOY = new Brick(new DependencyCodegenHints("Foy",
            List.of("META-INF/web-fragment.xml"), List.of(), List.of("META-INF/io.vidocq.foy/generated"), List.of()),
            "g:foy-codegen:1", List.of());

    private ScanSelection.Dependency jar(String artifactId, String... entries) throws IOException {
        Path file = tmp.resolve(artifactId + ".jar");
        try (var out = new JarOutputStream(Files.newOutputStream(file))) {
            for (String entry : entries) {
                out.putNextEntry(new JarEntry(entry));
                out.closeEntry();
            }
        }
        return new ScanSelection.Dependency("org.x", artifactId, "1.0", file);
    }

    static ScanSelection.Decision decision(String artifactId, ScanSelection.JarFacts facts,
                                           ScanSelection.Source source, String excluded) {
        return new ScanSelection.Decision(new ScanSelection.Dependency("org.x", artifactId, "1.0",
                Path.of(artifactId + ".jar")), facts, source, excluded);
    }

    @Test
    void reportsEachConcernedJarAndTheBlockThatMakesTheDetectionExplicit() {
        var explicit = new ScanSelection.JarFacts(true, "annotated", false, false, false, List.of());
        var automatic = new ScanSelection.JarFacts(true, "all", false, false, true, List.of());
        var processed = new ScanSelection.JarFacts(true, "annotated", true, false, false, List.of());
        var plain = new ScanSelection.JarFacts(false, null, false, false, true, List.of());

        String report = AnalyzeReport.render(List.of(
                decision("server", explicit, ScanSelection.Source.EXTENSION, null),
                decision("auto", automatic, ScanSelection.Source.AUTOMATIC, null),
                decision("brick", processed, ScanSelection.Source.AUTOMATIC, "already carries generated code"),
                decision("slf4j", plain, null, null)));

        assertEquals("""
                Dependencies vidocq:generate scans for CDI beans:
                  org.x:server:1.0 — bean archive (annotated), explicit module
                    scanned: declared by an extension → enriched copy
                  org.x:auto:1.0 — bean archive (all), automatic module
                    scanned: a CDI bean archive → open module synthesized, then enriched copy
                  org.x:brick:1.0 — bean archive (annotated), explicit module
                    not scanned: already carries generated code
                1 other dependency is not a bean archive and is not named.

                To make the detected bean archives explicit:
                <scanDependencies>
                    <scanDependency>org.x:auto</scanDependency>
                </scanDependencies>
                """, report);
    }

    @Test
    void aJarABrickSelectedNamesTheBricks() {
        var plain = new ScanSelection.JarFacts(false, null, false, false, true, List.of());
        var weblib = new ScanSelection.Decision(new ScanSelection.Dependency("org.x", "weblib", "1.0",
                Path.of("weblib.jar")), plain, ScanSelection.Source.BRICK, null, List.of("Foy"));

        assertEquals("""
                Dependencies vidocq:generate scans for CDI beans:
                  org.x:weblib:1.0 — not a bean archive, automatic module
                    scanned: selected by a brick (Foy) → open module synthesized, then enriched copy
                """, AnalyzeReport.render(List.of(weblib)));
    }

    @Test
    void theBrickSectionNamesTheMatchTheSkipAndTheCache() throws Exception {
        var vaadin = jar("vaadin", "META-INF/web-fragment.xml");
        var cachedJar = jar("cached", "META-INF/web-fragment.xml", "x.txt");
        var done = jar("done", "META-INF/web-fragment.xml", "META-INF/io.vidocq.foy/generated");
        var plain = jar("plain", "a.txt");
        var cache = new DependencyCodegenCache(tmp.resolve("cache"));
        cache.put(DependencyCodegenCache.key(cachedJar.jar(), List.of(FOY), 25), new DependencyCodegen.Output(
                Map.of("a/B.class", new byte[] {1}, "c.list", new byte[] {2}), List.of()));

        var lines = AnalyzeReport.brickDecisions(DependencyCodegenPass.select(List.of(FOY),
                List.of(vaadin, cachedJar, done, plain), List.of()), cache, 25);
        String report = AnalyzeReport.render(List.of(), lines);

        assertEquals("""
                Dependencies vidocq:generate scans for CDI beans:
                Brick codegen:
                  vaadin: Foy (resource META-INF/web-fragment.xml)
                  cached: Foy (resource META-INF/web-fragment.xml), cached (1 class)
                  done: Foy skipped (marker META-INF/io.vidocq.foy/generated)
                """, report);
    }

    @Test
    void anExcludedJarIsNotACandidateAndNoBrickMeansNoSection() throws Exception {
        var vaadin = jar("vaadin", "META-INF/web-fragment.xml");
        var cache = new DependencyCodegenCache(tmp.resolve("cache"));

        assertTrue(AnalyzeReport.brickDecisions(DependencyCodegenPass.select(List.of(FOY), List.of(vaadin),
                List.of("org.x:vaadin")), cache, 25).isEmpty());
        assertTrue(AnalyzeReport.brickDecisions(DependencyCodegenPass.select(List.of(), List.of(vaadin), List.of()),
                cache, 25).isEmpty());
        assertTrue(!AnalyzeReport.render(List.of(), List.of()).contains("Brick codegen"));
    }
}
