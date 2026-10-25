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
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Vidocq/vidocq#353: what {@code vidocq info} says about the project, read from its files without Maven. */
class ProjectSummaryTest {

    private static final String PARENT = """
            <project>
                <parent>
                    <groupId>io.vidocq.runtime</groupId>
                    <artifactId>vidocq-runtime-parent</artifactId>
                    <version>0.4.0</version>
                </parent>
                <artifactId>demo</artifactId>
                <dependencies>
                    <dependency>
                        <groupId>io.vidocq.runtime.extensions.jakartaee.core</groupId>
                        <artifactId>vidocq-runtime-cassini-rest-extension</artifactId>
                    </dependency>
                    <dependency>
                        <groupId>io.vidocq.runtime.extensions.microprofile</groupId>
                        <artifactId>vidocq-runtime-knock-health-extension</artifactId>
                    </dependency>
                    <dependency>
                        <groupId>org.junit.jupiter</groupId>
                        <artifactId>junit-jupiter</artifactId>
                        <scope>test</scope>
                    </dependency>
                </dependencies>
            </project>
            """;

    private static final String BOM_ONLY = """
            <project>
                <groupId>com.acme</groupId>
                <artifactId>demo</artifactId>
                <version>1.0.0</version>
                <properties>
                    <vidocq.mainModule>com.acme.fromthepom</vidocq.mainModule>
                </properties>
                <dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>io.vidocq.runtime</groupId>
                            <artifactId>vidocq-runtime-bom</artifactId>
                            <version>0.4.1</version>
                            <type>pom</type>
                            <scope>import</scope>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
            </project>
            """;

    private static final Map<String, String> NO_ENV = Map.of();

    @Test
    void aProjectIsSummarisedFromItsPomModuleInfoAndProperties(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("pom.xml"), PARENT);
        write(dir.resolve("src/main/java/module-info.java"), """
                /* Not this one: module com.acme.commented { } */
                // nor module com.acme.line {
                open module com.acme.demo {
                    requires jakarta.ws.rs;
                }
                """);
        write(dir.resolve("src/main/resources/vidocq.properties"), "vidocq.http.port = 8081\nvidocq.profile = staging\n");

        ProjectSummary summary = ProjectSummary.read(dir, NO_ENV::get);

        assertEquals("com.acme.demo", summary.mainModule());
        assertEquals("0.4.0", summary.runtimeVersion());
        assertEquals(List.of("cassini-rest", "knock-health"), summary.extensions());
        assertEquals("staging", summary.profile());
        assertEquals("vidocq.properties", summary.profileSource());
    }

    @Test
    void theEnvironmentChoosesTheProfileFirst(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("pom.xml"), PARENT);
        write(dir.resolve("src/main/resources/vidocq.properties"), "vidocq.profile = staging\n");

        ProjectSummary summary = ProjectSummary.read(dir, Map.of("VIDOCQ_PROFILE", "dev")::get);

        assertEquals("dev", summary.profile());
        assertEquals("VIDOCQ_PROFILE", summary.profileSource());
    }

    /** An application that keeps its own parent imports the BOM, and may name its module in the pom. */
    @Test
    void theBomGivesTheVersionAndThePomTheModuleWhenThereIsNoModuleInfo(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("pom.xml"), BOM_ONLY);

        ProjectSummary summary = ProjectSummary.read(dir, NO_ENV::get);

        assertEquals("com.acme.fromthepom", summary.mainModule());
        assertEquals("0.4.1", summary.runtimeVersion());
        assertEquals(List.of(), summary.extensions());
        assertNull(summary.profile());
        assertNull(summary.profileSource());
    }

    @Test
    void whatTheProjectDoesNotSayStaysUnknown(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("pom.xml"), "<project><artifactId>plain</artifactId></project>");

        ProjectSummary summary = ProjectSummary.read(dir, NO_ENV::get);

        assertNull(summary.mainModule());
        assertNull(summary.runtimeVersion());
        assertEquals(Optional.empty(), Optional.ofNullable(summary.profile()));
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
