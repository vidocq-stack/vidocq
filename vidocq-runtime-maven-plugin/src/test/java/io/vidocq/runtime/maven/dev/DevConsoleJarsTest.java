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
package io.vidocq.runtime.maven.dev;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.eclipse.aether.repository.RemoteRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DevConsoleJars#resolve} adds the dev console and its SPI from this plugin's own dependencies, and only when
 * the application already has the Chappe HTTP server extension (Vidocq/vidocq#143): a CLI application
 * must never get a listener started under {@code vidocq:dev}.
 */
class DevConsoleJarsTest {

    @Test
    void withoutChappeNoConsoleIsAdded(@TempDir Path dir) throws Exception {
        List<String> infos = new ArrayList<>();
        List<Path> jars = DevConsoleJars.resolve(pluginMapWithConsole(dir), List.of(), infos::add);

        assertEquals(List.of(), jars);
        assertTrue(infos.getFirst().contains("vidocq-runtime-chappe-webserver-extension"), infos.toString());
    }

    @Test
    void withChappeTheConsoleAndItsSpiAreAddedUnlessDeclared(@TempDir Path dir) throws Exception {
        Artifact chappe = artifact("io.vidocq.runtime.extensions.essentials",
                "vidocq-runtime-chappe-webserver-extension", plainJar(dir, "chappe.jar"));

        assertEquals(2, DevConsoleJars.resolve(pluginMapWithConsole(dir), List.of(chappe), i -> {}).size());
        Artifact declared = artifact("io.vidocq.runtime.extensions.essentials", "vidocq-runtime-devconsole-extension",
                markedJar(dir, "declared-console.jar"));
        assertEquals(1, DevConsoleJars.resolve(pluginMapWithConsole(dir), List.of(chappe, declared), i -> {}).size(),
                "never twice: the declared console is kept");
    }

    /**
     * The 4-arg overload records which keys it added (Vidocq/vidocq#143, spec §5.3 "never twice"): the caller
     * threads this set into {@link DevModules#collect}, so a companion's own dependency on the SPI is not added
     * again.
     */
    @Test
    void addedKeysRecordsWhatWasActuallyAdded(@TempDir Path dir) throws Exception {
        Artifact chappe = artifact("io.vidocq.runtime.extensions.essentials",
                "vidocq-runtime-chappe-webserver-extension", plainJar(dir, "chappe.jar"));
        Set<String> addedKeys = new HashSet<>();

        List<Path> jars = DevConsoleJars.resolve(pluginMapWithConsole(dir), List.of(chappe), i -> {}, addedKeys);

        assertEquals(2, jars.size());
        assertEquals(Set.of(DevConsoleJars.CONSOLE_KEY, DevConsoleJars.SPI_KEY), addedKeys);
    }

    /** Under vidocq:dev the dev services step already put the SPI on the path: the console step adds the console. */
    @Test
    void aJarAnEarlierStepAddedIsNotAddedAgain(@TempDir Path dir) throws Exception {
        Artifact chappe = artifact("io.vidocq.runtime.extensions.essentials",
                "vidocq-runtime-chappe-webserver-extension", plainJar(dir, "chappe.jar"));
        Set<String> onPath = new HashSet<>(Set.of(DevConsoleJars.SPI_KEY));

        List<Path> jars = DevConsoleJars.resolve(pluginMapWithConsole(dir), List.of(chappe), i -> {}, onPath);

        assertEquals(List.of(dir.resolve("console.jar")), jars);
        assertEquals(Set.of(DevConsoleJars.CONSOLE_KEY, DevConsoleJars.SPI_KEY), onPath);
    }

    /** Only what is neither declared nor already added needs resolving, so a declared console needs no repository. */
    @Test
    void onlyTheMissingKeysAreResolved(@TempDir Path dir) throws Exception {
        Artifact chappe = artifact("io.vidocq.runtime.extensions.essentials",
                "vidocq-runtime-chappe-webserver-extension", plainJar(dir, "chappe.jar"));
        Artifact console = artifact("io.vidocq.runtime.extensions.essentials", "vidocq-runtime-devconsole-extension",
                markedJar(dir, "declared-console.jar"));
        Artifact spi = artifact("io.vidocq.runtime", "vidocq-runtime-devconsole-spi", plainJar(dir, "spi.jar"));

        assertEquals(List.of(), DevConsoleJars.missingKeys(List.of(chappe, console, spi), Set.of()));
        assertEquals(List.of(DevConsoleJars.CONSOLE_KEY),
                DevConsoleJars.missingKeys(List.of(chappe), Set.of(DevConsoleJars.SPI_KEY)));
        assertEquals(List.of(DevConsoleJars.CONSOLE_KEY, DevConsoleJars.SPI_KEY),
                DevConsoleJars.missingKeys(List.of(chappe), Set.of()));
    }

    /** This plugin's own resolved dependencies: the console, a marked jar, and its SPI, an ordinary API jar. */
    static Map<String, Artifact> pluginMapWithConsole(Path dir) throws Exception {
        Map<String, Artifact> map = new HashMap<>();
        map.put(DevConsoleJars.CONSOLE_KEY, artifact("io.vidocq.runtime.extensions.essentials",
                "vidocq-runtime-devconsole-extension", markedJar(dir, "console.jar")));
        map.put(DevConsoleJars.SPI_KEY, artifact("io.vidocq.runtime", "vidocq-runtime-devconsole-spi",
                plainJar(dir, "console-spi.jar")));
        return map;
    }

    static Path markedJar(Path dir, String name) throws Exception {
        Path jar = dir.resolve(name);
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue(io.vidocq.runtime.maven.DevOnlyJars.MANIFEST_ENTRY, "true");
        try (OutputStream out = Files.newOutputStream(jar);
                JarOutputStream jarOut = new JarOutputStream(out, manifest)) {
            jarOut.putNextEntry(new ZipEntry("a/A.class"));
            jarOut.closeEntry();
        }
        return jar;
    }

    static Path plainJar(Path dir, String name) throws Exception {
        Path jar = dir.resolve(name);
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        try (OutputStream out = Files.newOutputStream(jar);
                JarOutputStream jarOut = new JarOutputStream(out, manifest)) {
            jarOut.putNextEntry(new ZipEntry("a/A.class"));
            jarOut.closeEntry();
        }
        return jar;
    }

    /** #148: the console follows the application's Vidocq runtime (its Chappe extension), else this plugin. */
    @Test
    void theConsoleVersionIsTheRuntimesElseThePlugins() {
        Artifact chappe = new DefaultArtifact("io.vidocq.runtime.extensions.essentials",
                "vidocq-runtime-chappe-webserver-extension", "0.3.0-20260930.101010-4", "compile", "jar", null,
                new DefaultArtifactHandler("jar"));

        assertEquals("0.3.0-SNAPSHOT", DevConsoleJars.runtimeVersion(List.of(chappe), "0.4.0-SNAPSHOT"));
        assertEquals("0.4.0-SNAPSHOT", DevConsoleJars.runtimeVersion(List.of(), "0.4.0-SNAPSHOT"));
    }

    @Test
    void aRuntimeOtherThanThePluginIsSaid() {
        String warning = DevConsoleJars.versionWarning("0.3.0-SNAPSHOT", "0.4.0-SNAPSHOT");

        assertTrue(warning.contains("0.3.0-SNAPSHOT") && warning.contains("0.4.0-SNAPSHOT"), warning);
        assertNull(DevConsoleJars.versionWarning("0.4.0-SNAPSHOT", "0.4.0-SNAPSHOT"));
    }

    /** #148: a snapshot repository declared only as a plugin repository is searched too, each repository once. */
    @Test
    void theProjectsRepositoriesThenThePluginsAreSearchedEachOnce() {
        RemoteRepository central = new RemoteRepository.Builder("central", "default", "https://repo/central").build();
        RemoteRepository snapshots = new RemoteRepository.Builder("vidocq-snapshots", "default", "https://repo/s")
                .build();

        assertEquals(List.of(central, snapshots),
                DevConsoleJars.repositories(List.of(central), List.of(central, snapshots)));
        assertEquals(List.of(central), DevConsoleJars.repositories(List.of(central), null));
    }

    static Artifact artifact(String groupId, String artifactId, Path jar) {
        Artifact artifact = new DefaultArtifact(groupId, artifactId, "1.0", "runtime", "jar", null,
                new DefaultArtifactHandler("jar"));
        artifact.setFile(jar.toFile());
        return artifact;
    }
}
