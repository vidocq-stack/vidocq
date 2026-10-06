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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PomEditorTest {

    private static final String POM_WITH_DEPS = """
            <project>
                <artifactId>app</artifactId>
                <dependencies>
                    <dependency>
                        <groupId>io.vidocq.runtime</groupId>
                        <artifactId>vidocq-runtime-core</artifactId>
                    </dependency>
                </dependencies>
            </project>
            """;

    private static final ExtensionCoordinate KNOCK = new ExtensionCoordinate(
            "io.vidocq.runtime.extensions.microprofile", "vidocq-runtime-knock-health-extension");

    @Test
    void addInsertsBeforeClosingDependencies() {
        PomEditor.Result r = PomEditor.add(POM_WITH_DEPS, KNOCK);
        assertTrue(r.changed());
        assertTrue(r.pom().contains("vidocq-runtime-knock-health-extension"));
        // new dependency sits before </dependencies> and after the existing one
        int core = r.pom().indexOf("vidocq-runtime-core");
        int knock = r.pom().indexOf("vidocq-runtime-knock-health-extension");
        int close = r.pom().indexOf("</dependencies>");
        assertTrue(core < knock && knock < close);
        // existing content preserved
        assertTrue(r.pom().contains("<artifactId>app</artifactId>"));
        // result parses and now contains the coordinate
        assertTrue(PomDependencies.contains(r.pom(), KNOCK));
    }

    @Test
    void addIsIdempotent() {
        PomEditor.Result once = PomEditor.add(POM_WITH_DEPS, KNOCK);
        PomEditor.Result twice = PomEditor.add(once.pom(), KNOCK);
        assertFalse(twice.changed());
        assertEquals(once.pom(), twice.pom());
    }

    @Test
    void addCreatesDependenciesBlockWhenAbsent() {
        String pom = """
                <project>
                    <artifactId>app</artifactId>
                </project>
                """;
        PomEditor.Result r = PomEditor.add(pom, KNOCK);
        assertTrue(r.changed());
        assertTrue(r.pom().contains("<dependencies>"));
        assertTrue(r.pom().contains("</dependencies>"));
        assertTrue(PomDependencies.contains(r.pom(), KNOCK));
        int deps = r.pom().indexOf("<dependencies>");
        int proj = r.pom().indexOf("</project>");
        assertTrue(deps < proj);
    }

    @Test
    void removeDeletesMatchingBlock() {
        String withKnock = PomEditor.add(POM_WITH_DEPS, KNOCK).pom();
        PomEditor.Result r = PomEditor.remove(withKnock, KNOCK);
        assertTrue(r.changed());
        assertFalse(r.pom().contains("vidocq-runtime-knock-health-extension"));
        // the core dependency is untouched
        assertTrue(PomDependencies.contains(r.pom(),
                new ExtensionCoordinate("io.vidocq.runtime", "vidocq-runtime-core")));
    }

    @Test
    void removeIsNoOpWhenAbsent() {
        PomEditor.Result r = PomEditor.remove(POM_WITH_DEPS, KNOCK);
        assertFalse(r.changed());
        assertEquals(POM_WITH_DEPS, r.pom());
    }

    @Test
    void removeDoesNotTouchDependencyManagement() {
        String pom = """
                <project>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>io.vidocq.runtime</groupId>
                                <artifactId>vidocq-runtime-core</artifactId>
                                <version>1.0</version>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>io.vidocq.runtime</groupId>
                            <artifactId>vidocq-runtime-core</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """;
        ExtensionCoordinate core = new ExtensionCoordinate("io.vidocq.runtime", "vidocq-runtime-core");
        PomEditor.Result r = PomEditor.remove(pom, core);
        assertTrue(r.changed());
        // the managed entry (with <version>) survives, project-level one is gone
        assertTrue(r.pom().contains("<version>1.0</version>"));
        assertFalse(PomDependencies.contains(r.pom(), core));
    }

    @Test
    void addThrowsOnPomWithoutProjectClose() {
        assertThrows(IllegalArgumentException.class,
                () -> PomEditor.add("<project><artifactId>x</artifactId>", KNOCK));
    }

    @Test
    void roundTripAddRemoveRestoresOriginal() {
        String added = PomEditor.add(POM_WITH_DEPS, KNOCK).pom();
        String removed = PomEditor.remove(added, KNOCK).pom();
        assertEquals(POM_WITH_DEPS, removed);
    }

    @Test
    void addPinsTheGivenVersion() {
        PomEditor.Result r = PomEditor.add(POM_WITH_DEPS, KNOCK, "0.4.0");

        String added = r.pom().substring(r.pom().indexOf("vidocq-runtime-knock-health-extension"));
        assertTrue(added.substring(0, added.indexOf("</dependency>")).contains("<version>0.4.0</version>"),
                "pom was:\n" + r.pom());
    }
}
