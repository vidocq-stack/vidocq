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

import io.vidocq.runtime.tooling.PomConverter.Target;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Vidocq/vidocq#380: {@code vidocq pom convert --to parentless|parent} turns a pom from one style into the other,
 * idempotently, keeping everything that is not the style: dependencies, properties, plugins, comments, indentation.
 * The two styles are the ones {@code vidocq create} writes, so each conversion is checked against the scaffolder.
 */
class PomConverterTest {

    private static final String RUNTIME = "0.4.0";

    private static String scaffolded(boolean withParent, String runtime) {
        return ProjectScaffolder.buildPom(new ProjectSpec("demo", "com.acme", "com.acme.demo", Set.of("cassini-rest"),
                null, false, false, withParent), runtime, new RecordingReporter());
    }

    @Test
    void theParentStyleBecomesTheParentlessStyleTheScaffolderWrites() {
        PomConverter.Result result = PomConverter.convert(scaffolded(true, RUNTIME), Target.PARENTLESS);

        assertTrue(result.changed());
        assertEquals(scaffolded(false, RUNTIME), result.pom());
    }

    @Test
    void theParentlessStyleBecomesTheParentStyleTheScaffolderWrites() {
        PomConverter.Result result = PomConverter.convert(scaffolded(false, RUNTIME), Target.PARENT);

        assertTrue(result.changed());
        assertEquals(scaffolded(true, RUNTIME), result.pom());
    }

    private static final List<ExtensionCoordinate> DEV_SERVICES = List.of(
            new ExtensionCoordinate("io.vidocq.runtime", "vidocq-runtime-devservice-postgres"),
            new ExtensionCoordinate("io.vidocq.runtime", "vidocq-runtime-devservice-keycloak"),
            new ExtensionCoordinate("io.vidocq.runtime", "vidocq-runtime-devservice-otel"));

    /** The pom with the dev services on the plugin, as {@code vidocq extension add} writes them in that style. */
    private static String withDevServices(String pom, String version) {
        String edited = pom;
        for (ExtensionCoordinate service : DEV_SERVICES) {
            edited = PomEditor.addPluginDependency(edited, DevServiceWiring.PLUGIN, service, version, null).pom();
        }
        return edited;
    }

    @Test
    void theDevServicesOfThePluginFollowTheVersionTheStyleUses() {
        String underParent = withDevServices(scaffolded(true, RUNTIME), "${project.parent.version}");
        String parentless = withDevServices(scaffolded(false, RUNTIME), "${vidocq.version}");

        PomConverter.Result toParentless = PomConverter.convert(underParent, Target.PARENTLESS);
        PomConverter.Result toParent = PomConverter.convert(parentless, Target.PARENT);

        assertEquals(parentless, toParentless.pom());
        assertFalse(toParentless.pom().contains("project.parent.version"));
        assertEquals(underParent, toParent.pom());
    }

    @Test
    void aDevServicePinnedToALiteralVersionIsKept() {
        String pinned = withDevServices(scaffolded(true, RUNTIME), "0.4.0");

        assertEquals(withDevServices(scaffolded(false, RUNTIME), "0.4.0"),
                PomConverter.convert(pinned, Target.PARENTLESS).pom());
    }

    @Test
    void convertingToTheStyleAPomAlreadyHasChangesNothing() {
        for (Target target : Target.values()) {
            String pom = scaffolded(target == Target.PARENT, RUNTIME);

            PomConverter.Result result = PomConverter.convert(pom, target);

            assertFalse(result.changed(), target.toString());
            assertEquals(pom, result.pom(), target.toString());
        }
    }

    @Test
    void aSnapshotKeepsItsRepositoriesBothWays() {
        String parent = scaffolded(true, "0.4.0-SNAPSHOT");
        String parentless = scaffolded(false, "0.4.0-SNAPSHOT");

        assertEquals(parentless, PomConverter.convert(parent, Target.PARENTLESS).pom());
        assertEquals(parent, PomConverter.convert(parentless, Target.PARENT).pom());
    }

    /** A company parent stays: the Vidocq part is the BOM, the plugin as an extension and the release. */
    @Test
    void aCompanyParentKeepsItsParentAndGetsTheBomAndTheExtension() {
        String pom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>

                    <parent>
                        <groupId>com.acme.build</groupId>
                        <artifactId>acme-parent</artifactId>
                        <version>7.1.0</version>
                        <relativePath/>
                    </parent>

                    <groupId>com.acme</groupId>
                    <artifactId>todo</artifactId>
                    <version>2.0.0</version>

                    <properties>
                        <vidocq.mainModule>com.acme.todo</vidocq.mainModule>
                    </properties>

                    <dependencies>
                        <dependency>
                            <groupId>io.vidocq.runtime</groupId>
                            <artifactId>vidocq-runtime-core</artifactId>
                            <version>0.4.0</version>
                        </dependency>
                    </dependencies>

                    <build>
                        <plugins>
                            <!-- Our own enforcer rules. -->
                            <plugin>
                                <groupId>io.vidocq.runtime</groupId>
                                <artifactId>vidocq-runtime-maven-plugin</artifactId>
                                <version>0.4.0</version>
                            </plugin>
                        </plugins>
                    </build>
                </project>
                """;

        String converted = PomConverter.convert(pom, Target.PARENTLESS).pom();

        assertTrue(converted.contains("<artifactId>acme-parent</artifactId>"), converted);
        assertTrue(converted.contains("<vidocq.version>0.4.0</vidocq.version>"), converted);
        assertTrue(converted.contains("<maven.compiler.release>25</maven.compiler.release>"), converted);
        assertTrue(converted.contains("""
                            <dependency>
                                <groupId>io.vidocq.runtime</groupId>
                                <artifactId>vidocq-runtime-bom</artifactId>
                                <version>${vidocq.version}</version>
                                <type>pom</type>
                                <scope>import</scope>
                            </dependency>
                """), converted);
        assertTrue(converted.contains("""
                            <!-- Our own enforcer rules. -->
                            <plugin>
                                <groupId>io.vidocq.runtime</groupId>
                                <artifactId>vidocq-runtime-maven-plugin</artifactId>
                                <version>${vidocq.version}</version>
                                <extensions>true</extensions>
                            </plugin>
                """), converted);
        assertTrue(converted.contains("<version>2.0.0</version>"), "the project's own version stays:\n" + converted);
        assertFalse(PomConverter.convert(converted, Target.PARENTLESS).changed(), "idempotent");
    }

    @Test
    void aCompanyParentCannotTakeTheVidocqParent() {
        String pom = scaffolded(false, RUNTIME).replace("    <groupId>com.acme</groupId>", """
                    <parent>
                        <groupId>com.acme.build</groupId>
                        <artifactId>acme-parent</artifactId>
                        <version>7.1.0</version>
                    </parent>

                    <groupId>com.acme</groupId>""");

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> PomConverter.convert(pom, Target.PARENT));

        assertTrue(refused.getMessage().contains("com.acme.build:acme-parent"), refused.getMessage());
    }

    @Test
    void aPomWithoutTheVidocqPluginIsNotRecognised() {
        String pom = """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.acme</groupId>
                    <artifactId>lib</artifactId>
                    <version>1.0</version>
                </project>
                """;

        for (Target target : Target.values()) {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> PomConverter.convert(pom, target), target.toString());
            assertTrue(refused.getMessage().contains("vidocq-runtime-maven-plugin"), refused.getMessage());
        }
    }

    /** The pom of a Vaadin application (the spike of #380): what is not the style survives both ways. */
    @Test
    void whatIsNotTheStyleIsKept() throws IOException {
        String vaadin = resource("vaadin-parentless-pom.xml");

        String parent = PomConverter.convert(vaadin, Target.PARENT).pom();
        String back = PomConverter.convert(parent, Target.PARENTLESS).pom();
        String forward = PomConverter.convert(vaadin, Target.PARENTLESS).pom();

        for (String kept : List.of("<!-- Vaadin reads production mode as a system property of the running JVM. -->",
                "<artifactId>vaadin-maven-plugin</artifactId>", "<artifactId>vaadin-bom</artifactId>",
                "<!-- mvn -Pjlink clean package vidocq:jlink : a self-contained image with its own JDK. -->",
                "<vidocq.jvmArgs>-Dvaadin.productionMode=true</vidocq.jvmArgs>", "<artifactId>slf4j-jdk14</artifactId>")) {
            assertTrue(parent.contains(kept), kept + " lost:\n" + parent);
        }
        assertTrue(parent.contains("""
                    <modelVersion>4.0.0</modelVersion>

                    <parent>
                        <groupId>io.vidocq.runtime</groupId>
                        <artifactId>vidocq-runtime-parent</artifactId>
                        <version>0.4.0-SNAPSHOT</version>
                        <relativePath/>
                    </parent>
                """), parent);
        // vauban-maven-plugin still reads ${vidocq.version}: the property stays.
        assertTrue(parent.contains("<vidocq.version>0.4.0-SNAPSHOT</vidocq.version>"), parent);
        assertFalse(parent.contains("vidocq-runtime-bom"), "the parent imports the BOM:\n" + parent);
        assertFalse(parent.contains("junit-bom"), "the parent imports junit-bom at that version:\n" + parent);
        assertFalse(parent.contains("<extensions>true</extensions>"), parent);
        assertFalse(PomConverter.convert(parent, Target.PARENT).changed(), "idempotent");

        // The way back adds the one line the spike's pom lacked, the release IntelliJ reads. The parent sets the
        // UTF-8 encoding the pom spelled out, and the way back writes it first among the properties again.
        assertEquals(forward.lines().sorted().toList(), back.lines().sorted().toList());
        List<String> added = TextDiff.unified(vaadin, forward).lines().filter(l -> l.startsWith("+") && !l.startsWith("+++")).toList();
        assertEquals(1, added.size(), TextDiff.unified(vaadin, forward));
        assertTrue(added.getFirst().contains("<maven.compiler.release>25</maven.compiler.release>"), added.toString());
    }

    @Test
    void theDiffNamesTheLinesThatChange() {
        String before = "a\nb\nc\n";
        String after = "a\nB\nc\nd\n";

        String diff = TextDiff.unified(before, after);

        assertTrue(diff.contains("-b\n"), diff);
        assertTrue(diff.contains("+B\n"), diff);
        assertTrue(diff.contains("+d\n"), diff);
        assertTrue(diff.contains(" a\n"), "context lines are kept:\n" + diff);
        assertEquals("", TextDiff.unified(before, before));
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = PomConverterTest.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IOException(name + " is not on the test class path");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
