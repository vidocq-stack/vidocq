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

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Vidocq/vidocq#510: {@code vidocq pom upgrade} moves an application to another Vidocq version in both pom styles
 * and in a pom written before 0.4.0, idempotently, and reports versions that disagree instead of aligning them.
 */
class PomUpgraderTest {

    private static String scaffolded(boolean withParent, String runtime) {
        return ProjectScaffolder.buildPom(new ProjectSpec("demo", "com.acme", "com.acme.demo", Set.of("cassini-rest"),
                null, false, false, withParent), runtime, new RecordingReporter());
    }

    /** A pom as 0.3.0 wrote it: no parent, a literal version on the BOM, the plugin and the dependencies. */
    private static String before040(String bom, String plugin, String dependency) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.acme</groupId>
                    <artifactId>demo</artifactId>
                    <version>1.0.0</version>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>io.vidocq.runtime</groupId>
                                <artifactId>vidocq-runtime-bom</artifactId>
                                <version>%s</version>
                                <type>pom</type>
                                <scope>import</scope>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>io.vidocq.runtime</groupId>
                            <artifactId>vidocq-runtime-core</artifactId>
                            <version>%s</version>
                        </dependency>
                        <dependency>
                            <groupId>org.junit.jupiter</groupId>
                            <artifactId>junit-jupiter</artifactId>
                            <version>5.11.0</version>
                        </dependency>
                    </dependencies>
                    <build>
                        <plugins>
                            <plugin>
                                <groupId>io.vidocq.runtime</groupId>
                                <artifactId>vidocq-runtime-maven-plugin</artifactId>
                                <version>%s</version>
                            </plugin>
                        </plugins>
                    </build>
                </project>
                """.formatted(bom, dependency, plugin);
    }

    @Test
    void theParentlessStyleCarriesTheVersionOnceInTheProperty() {
        String pom = scaffolded(false, "0.4.0");

        assertEquals("0.4.0", PomUpgrader.current(pom).version());
        PomUpgrader.Result result = PomUpgrader.upgrade(pom, "0.5.0");

        assertTrue(result.changed());
        assertEquals(pom.replace("<vidocq.version>0.4.0</vidocq.version>", "<vidocq.version>0.5.0</vidocq.version>"),
                result.pom());
    }

    @Test
    void theParentStyleCarriesTheVersionInTheParent() {
        String pom = scaffolded(true, "0.4.0");

        assertEquals("0.4.0", PomUpgrader.current(pom).version());
        PomUpgrader.Result result = PomUpgrader.upgrade(pom, "0.5.0");

        assertTrue(result.changed());
        assertEquals(pom.replace("<version>0.4.0</version>", "<version>0.5.0</version>"), result.pom());
    }

    @Test
    void aPomBeforeZeroFourZeroHasItsLiteralVersionsMovedTogether() {
        String pom = before040("0.3.0", "0.3.0", "0.3.0");

        assertEquals("0.3.0", PomUpgrader.current(pom).version());
        PomUpgrader.Result result = PomUpgrader.upgrade(pom, "0.4.0");

        assertEquals(before040("0.4.0", "0.4.0", "0.4.0"), result.pom());
        assertTrue(result.pom().contains("<version>5.11.0</version>"), "the other dependencies keep their version");
    }

    @Test
    void versionsThatDisagreeAreReportedAndNothingIsAligned() {
        String pom = before040("0.4.0", "0.3.0", "0.4.0");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> PomUpgrader.current(pom));
        assertThrows(IllegalArgumentException.class, () -> PomUpgrader.upgrade(pom, "0.5.0"));

        assertTrue(e.getMessage().contains("disagree"), e.getMessage());
        assertTrue(e.getMessage().contains("vidocq-runtime-maven-plugin 0.3.0"), e.getMessage());
        assertTrue(e.getMessage().contains("vidocq-runtime-bom 0.4.0"), e.getMessage());
    }

    @Test
    void aLiteralLeftBehindNextToThePropertyDisagrees() {
        String pom = scaffolded(false, "0.4.0").replace("<artifactId>vidocq-runtime-core</artifactId>",
                "<artifactId>vidocq-runtime-core</artifactId>\n            <version>0.3.0</version>");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> PomUpgrader.current(pom));

        assertTrue(e.getMessage().contains("vidocq-runtime-core 0.3.0"), e.getMessage());
    }

    @Test
    void upgradingToTheVersionTheyHaveChangesNothing() {
        for (String pom : new String[] {scaffolded(false, "0.4.0"), scaffolded(true, "0.4.0"),
                before040("0.4.0", "0.4.0", "0.4.0")}) {
            PomUpgrader.Result result = PomUpgrader.upgrade(pom, "0.4.0");

            assertFalse(result.changed());
            assertEquals(pom, result.pom());
        }
    }

    @Test
    void upgradingTwiceIsTheSameAsOnce() {
        String once = PomUpgrader.upgrade(scaffolded(false, "0.4.0"), "0.5.0").pom();

        assertEquals(once, PomUpgrader.upgrade(once, "0.5.0").pom());
    }

    @Test
    void aSnapshotTargetGetsTheSnapshotRepositoriesTheScaffolderWrites() {
        for (boolean withParent : new boolean[] {false, true}) {
            PomUpgrader.Result result = PomUpgrader.upgrade(scaffolded(withParent, "0.4.0"), "0.5.0-SNAPSHOT");

            assertEquals(scaffolded(withParent, "0.5.0-SNAPSHOT"), result.pom(), "withParent=" + withParent);
        }
    }

    @Test
    void aPomThatAlreadyHasTheSnapshotRepositoriesIsNotGivenThemTwice() {
        String snapshot = scaffolded(false, "0.5.0-SNAPSHOT");

        assertEquals(scaffolded(false, "0.6.0-SNAPSHOT"), PomUpgrader.upgrade(snapshot, "0.6.0-SNAPSHOT").pom());
    }

    @Test
    void aSnapshotPomMovedToAReleaseKeepsItsRepositories() {
        String pom = PomUpgrader.upgrade(scaffolded(false, "0.5.0-SNAPSHOT"), "0.5.0").pom();

        assertTrue(pom.contains("<vidocq.version>0.5.0</vidocq.version>"), pom);
        assertTrue(pom.contains("vidocq-snapshots"), pom);
    }

    @Test
    void aPomWithRepositoriesOfItsOwnIsToldToAddTheSnapshotOnes() {
        String own = """
                    <repositories>
                        <repository>
                            <id>acme</id>
                            <url>https://repo.acme.test/</url>
                        </repository>
                    </repositories>

                    <dependencyManagement>""";
        String pom = scaffolded(false, "0.4.0").replace("    <dependencyManagement>", own);

        PomUpgrader.Result result = PomUpgrader.upgrade(pom, "0.5.0-SNAPSHOT");

        assertTrue(result.pom().contains("<vidocq.version>0.5.0-SNAPSHOT</vidocq.version>"));
        assertEquals(1, result.notes().size());
        assertTrue(result.notes().get(0).contains("maven.vidocq.dev/snapshots"), result.notes().toString());
    }

    @Test
    void aPomWithoutAVidocqVersionIsRefused() {
        String plain = "<project><modelVersion>4.0.0</modelVersion><groupId>a</groupId><artifactId>b</artifactId>"
                + "<version>1</version></project>\n";

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> PomUpgrader.current(plain));

        assertTrue(e.getMessage().contains("no Vidocq version"), e.getMessage());
    }

    @Test
    void aBlankTargetIsRefused() {
        String pom = scaffolded(false, "0.4.0");

        assertThrows(IllegalArgumentException.class, () -> PomUpgrader.upgrade(pom, " "));
    }
}
