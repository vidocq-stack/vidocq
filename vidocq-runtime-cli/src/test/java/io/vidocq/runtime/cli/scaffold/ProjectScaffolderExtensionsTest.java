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
package io.vidocq.runtime.cli.scaffold;

import io.vidocq.runtime.cli.Command;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectScaffolderExtensionsTest {

    private static String scaffoldPom(Path dir, Set<String> extensions) throws IOException {
        ProjectScaffolder.scaffold(
                new Command.Create("demo", "com.acme", "com.acme.demo", extensions, null), dir);
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
    void unknownExtensionFallsBackToConvention(@TempDir Path dir) throws IOException {
        String pom = scaffoldPom(dir, Set.of("acme-widgets"));

        assertTrue(pom.contains("<artifactId>vidocq-runtime-acme-widgets-extension</artifactId>"),
                "unknown ids keep the io.vidocq.runtime:<convention> coordinate, pom was:\n" + pom);
    }

    @Test
    void scaffoldedPomWiresTheVidocqPluginAndLaunchCoordinates(@TempDir Path dir) throws IOException {
        String pom = scaffoldPom(dir, Set.of());

        assertTrue(pom.contains("<vidocq.mainModule>com.acme.demo</vidocq.mainModule>"),
                "vidocq:dev/jlink need the main module coordinates, pom was:\n" + pom);
        assertTrue(pom.contains("<vidocq.mainClass>com.acme.demo.DemoApp</vidocq.mainClass>"));
        assertTrue(pom.contains("<artifactId>vidocq-runtime-maven-plugin</artifactId>"),
                "the vidocq plugin must be active so mvn package produces a runnable dist");
        assertTrue(pom.contains("<goal>package</goal>"));
        // Workarounds for the released 0.2.0 plugin (see BUG.md): jvmArgs NPEs when
        // absent, and the launcher script needs the module/class --module syntax.
        assertTrue(pom.contains("<jvmArgs>"),
                "vidocq:package 0.2.0 NPEs when jvmArgs is not configured");
        assertTrue(pom.contains("<mainClass>${vidocq.mainModule}/${vidocq.mainClass}</mainClass>"),
                "vidocq:package 0.2.0 emits --module <mainClass> verbatim; it needs module/class");
    }

    @Test
    void extensionWithCodegenBundleGetsAnAnnotationProcessorPath(@TempDir Path dir) throws IOException {
        String pom = scaffoldPom(dir, Set.of("cassini-rest"));

        assertTrue(pom.contains("<artifactId>vidocq-runtime-cassini-rest-extension-codegen</artifactId>"),
                "checkpom requires the codegen bundle on the APT path, pom was:\n" + pom);
        assertTrue(pom.contains("combine.children=\"append\""),
                "the APT path must append to the parent's Vauban indexer entry");
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
                new Command.Create("demo", "com.acme", "com.acme.demo",
                        Set.of("cassini-rest"), null), dir);

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
