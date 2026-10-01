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

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AnalyzeReportTest {

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
}
