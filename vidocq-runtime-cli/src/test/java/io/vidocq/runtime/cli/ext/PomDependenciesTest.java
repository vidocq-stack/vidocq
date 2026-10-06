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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PomDependenciesTest {

    private static final String POM = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.acme</groupId>
                <artifactId>app</artifactId>
                <dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>managed.only</groupId>
                            <artifactId>bom-artifact</artifactId>
                            <version>1.0</version>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
                <dependencies>
                    <dependency>
                        <groupId>io.vidocq.runtime</groupId>
                        <artifactId>vidocq-runtime-core</artifactId>
                    </dependency>
                    <dependency>
                        <groupId>io.vidocq.runtime.extensions.microprofile</groupId>
                        <artifactId>vidocq-runtime-knock-health-extension</artifactId>
                    </dependency>
                </dependencies>
            </project>
            """;

    @Test
    void readsOnlyProjectLevelDependencies() {
        List<ExtensionCoordinate> deps = PomDependencies.parse(POM);
        assertEquals(2, deps.size());
        assertTrue(deps.contains(new ExtensionCoordinate("io.vidocq.runtime", "vidocq-runtime-core")));
        assertTrue(deps.contains(new ExtensionCoordinate(
                "io.vidocq.runtime.extensions.microprofile", "vidocq-runtime-knock-health-extension")));
    }

    @Test
    void ignoresDependencyManagementEntries() {
        List<ExtensionCoordinate> deps = PomDependencies.parse(POM);
        assertTrue(deps.stream().noneMatch(d -> d.artifactId().equals("bom-artifact")));
    }

    @Test
    void containsMatchesExactCoordinate() {
        assertTrue(PomDependencies.contains(POM,
                new ExtensionCoordinate("io.vidocq.runtime", "vidocq-runtime-core")));
        assertEquals(false, PomDependencies.contains(POM,
                new ExtensionCoordinate("io.vidocq.runtime", "vidocq-runtime-missing-extension")));
    }

    @Test
    void throwsOnInvalidXml() {
        assertThrows(IllegalArgumentException.class, () -> PomDependencies.parse("<project><oops"));
    }

    @Test
    void emptyWhenNoDependencies() {
        String pom = "<project><artifactId>x</artifactId></project>";
        assertTrue(PomDependencies.parse(pom).isEmpty());
    }

    @Test
    void readsTheParentCoordinates() {
        String pom = """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <parent>
                        <groupId>io.vidocq.runtime</groupId>
                        <artifactId>vidocq-runtime-parent</artifactId>
                        <version>0.4.0-SNAPSHOT</version>
                        <relativePath/>
                    </parent>
                    <artifactId>app</artifactId>
                    <version>1.0</version>
                </project>
                """;

        assertEquals(java.util.Optional.of(new PomDependencies.Parent(
                "io.vidocq.runtime", "vidocq-runtime-parent", "0.4.0-SNAPSHOT")),
                PomDependencies.parent(pom));
        assertTrue(PomDependencies.parent(POM).isEmpty());
    }
}
