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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectScaffolderExtensionsTest {

    private static final String RUNTIME = "1.2.3";
    private static final Reporter SILENT = new RecordingReporter();

    private static String scaffoldPom(Path dir, Set<String> extensions) throws IOException {
        ProjectScaffolder.scaffold(
                new ProjectSpec("demo", "com.acme", "com.acme.demo", extensions, null, false), dir, RUNTIME, SILENT);
        return Files.readString(dir.resolve("demo/pom.xml"));
    }

    @Test
    void knownExtensionUsesItsCatalogGroupId(@TempDir Path dir) throws IOException {
        String pom = scaffoldPom(dir, Set.of("cassini-rest"));

        assertTrue(pom.contains("<groupId>io.vidocq.runtime.extensions.jakartaee.core</groupId>"),
                "cassini-rest must use its category groupId from the catalog, pom was:\n" + pom);
        assertTrue(pom.contains("<artifactId>vidocq-runtime-cassini-rest-extension</artifactId>"));
        assertFalse(pom.contains("""
                        <groupId>io.vidocq.runtime</groupId>
                        <artifactId>vidocq-runtime-cassini-rest-extension</artifactId>"""),
                "the generic io.vidocq.runtime groupId must not be emitted for a catalog extension");
    }

    @Test
    void scaffoldedPomWiresTheVidocqPluginAndLaunchCoordinates(@TempDir Path dir) throws IOException {
        String pom = scaffoldPom(dir, Set.of());

        assertTrue(pom.contains("<vidocq.mainModule>com.acme.demo</vidocq.mainModule>"),
                "vidocq:dev/run/package boot the application from its main module, pom was:\n" + pom);
        assertTrue(pom.contains("<artifactId>vidocq-runtime-maven-plugin</artifactId>"),
                "the vidocq plugin must be active so mvn package produces a runnable dist");
        assertTrue(pom.contains("<goal>package</goal>"));
        // BUG-20261024-07: no workaround for the 0.2.0 plugin (BUG-20260710-01, fixed by #40).
        assertFalse(pom.contains("<jvmArgs>"), pom);
        assertFalse(pom.contains("BUG-2026"), pom);
    }

    /** BUG-20261031-05, -06, -07: a created project with the pool gets its dev service as `extension add` wires it. */
    @Test
    void anExtensionWithADevServiceIsCreatedWithWhatItNeeds(@TempDir Path dir) throws IOException {
        String pom = scaffoldPom(dir, Set.of("mansart-pool", "cervantes-jwt"));

        assertEquals(List.of(), DevServiceWiring.missing(List.of("mansart-pool", "cervantes-jwt"),
                DevServiceWiring.Declared.of(pom)), pom);
        assertTrue(pom.contains("<goal>package</goal>"), "the plugin keeps its execution: " + pom);
    }

    /** BUG-20261031-09: `create -x` writes the configuration of its extensions, as `extension add` does. */
    @Test
    void aCreatedProjectGetsTheConfigurationOfItsExtensions(@TempDir Path dir) throws IOException {
        scaffoldPom(dir, Set.of("humboldt-telemetry"));

        String config = Files.readString(dir.resolve("demo/src/main/resources/vidocq.properties"));
        assertEquals("demo", PropertiesText.entries(config).get("otel.service.name"), config);
        assertEquals("8080", PropertiesText.entries(config).get("vidocq.http.port"), config);
    }

    @Test
    void fromParent040AnExtensionIsItsDependencyAlone(@TempDir Path dir) throws IOException {
        String pom = scaffoldPom(dir, Set.of("cassini-rest"));

        // The parent loads the plugin as a build extension, which wires the codegen bundle (#339, evolution 2).
        assertFalse(pom.contains("codegen"), pom);
        assertFalse(pom.contains("annotationProcessorPaths"), pom);
    }

    @Test
    void underAParentBefore040AnExtensionWithCodegenBundleGetsAnAnnotationProcessorPath(@TempDir Path dir)
            throws IOException {
        ProjectScaffolder.scaffold(new ProjectSpec("demo", "com.acme", "com.acme.demo", Set.of("cassini-rest"),
                "0.3.0", false), dir, RUNTIME, SILENT);
        String pom = Files.readString(dir.resolve("demo/pom.xml"));

        assertTrue(pom.contains("<artifactId>vidocq-runtime-cassini-rest-extension-codegen</artifactId>"),
                "checkpom requires the codegen bundle on the APT path, pom was:\n" + pom);
        assertTrue(pom.contains("combine.children=\"append\""),
                "the APT path must append to the parent's Vauban indexer entry");
        assertFalse(pom.contains("<type>pom</type>"),
                "IntelliJ's Maven import ignores a processor path of type pom (Vidocq/vidocq#237), pom was:\n" + pom);
    }

    @Test
    void extensionWithoutCodegenBundleGetsNoAnnotationProcessorPath(@TempDir Path dir) throws IOException {
        String pom = scaffoldPom(dir, Set.of("knock-health"));

        assertFalse(pom.contains("annotationProcessorPaths"),
                "no APT block is needed when no selected extension has a codegen bundle, pom was:\n" + pom);
    }

    @Test
    void cassiniRestScaffoldsAReadyModuleInfo(@TempDir Path dir) throws IOException {
        ProjectScaffolder.scaffold(
                new ProjectSpec("demo", "com.acme", "com.acme.demo",
                        Set.of("cassini-rest"), null, false), dir, RUNTIME, SILENT);

        String moduleInfo = Files.readString(dir.resolve("demo/src/main/java/module-info.java"));
        assertTrue(moduleInfo.contains("requires jakarta.ws.rs;"),
                "REST resources need jakarta.ws.rs, module-info was:\n" + moduleInfo);
        assertTrue(moduleInfo.contains("requires io.vidocq.cassini.api;"));
        assertTrue(moduleInfo.contains("requires static java.compiler;"),
                "APT-generated adapters import @Generated");
        assertTrue(moduleInfo.contains("opens com.acme.demo;"),
                "JAX-RS and JSON-B reflect on resource classes");
    }

    @Test
    void explicitCoordinateIsEmittedVerbatim(@TempDir Path dir) throws IOException {
        String pom = scaffoldPom(dir, Set.of("com.acme:acme-extension"));

        assertTrue(pom.contains("<groupId>com.acme</groupId>"),
                "explicit groupId:artifactId ids must be emitted verbatim, pom was:\n" + pom);
        assertTrue(pom.contains("<artifactId>acme-extension</artifactId>"));
    }
}
