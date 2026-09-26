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
import org.apache.maven.plugin.MojoExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DevServicesExtensionJar#find} resolves the dev services extension jar, and {@link
 * DevServicesExtensionJar#resolve} the extension plus the jars it {@code requires} that are not already
 * on the application's own module path, from {@code ${plugin.artifactMap}} — the way Maven injects this
 * plugin's own resolved dependencies, a {@code groupId:artifactId} keyed map of {@link Artifact}.
 */
class DevServicesExtensionJarTest {

    private static final String EXTENSION_KEY = "io.vidocq.runtime:vidocq-runtime-devservices-extension";
    private static final String DEVCONSOLE_SPI_KEY = "io.vidocq.runtime:vidocq-runtime-devconsole-spi";

    @Test
    void findsTheJarOfTheDeclaredArtifact(@TempDir Path tmp) throws Exception {
        File jar = tmp.resolve("vidocq-runtime-devservices-extension-0.4.0-SNAPSHOT.jar").toFile();
        Artifact artifact = artifact("io.vidocq.runtime", "vidocq-runtime-devservices-extension", jar);

        Path found = DevServicesExtensionJar.find(Map.of(EXTENSION_KEY, artifact));

        assertEquals(jar.toPath(), found);
    }

    @Test
    void throwsNamingTheArtifactWhenAbsent() {
        MojoExecutionException failure = assertThrows(MojoExecutionException.class,
                () -> DevServicesExtensionJar.find(Map.of()));

        assertTrue(failure.getMessage().contains(EXTENSION_KEY), failure.getMessage());
    }

    @Test
    void throwsWhenTheMapItselfIsMissing() {
        MojoExecutionException failure = assertThrows(MojoExecutionException.class,
                () -> DevServicesExtensionJar.find(null));

        assertTrue(failure.getMessage().contains(EXTENSION_KEY), failure.getMessage());
    }

    @Test
    void throwsWhenTheArtifactWasNeverResolvedToAFile() {
        Artifact unresolved = new DefaultArtifact("io.vidocq.runtime", "vidocq-runtime-devservices-extension",
                "0.4.0-SNAPSHOT", "runtime", "jar", "", new DefaultArtifactHandler("jar"));
        // No setFile(...): as if Maven had never resolved it — must not NPE, must name the artifact.

        MojoExecutionException failure = assertThrows(MojoExecutionException.class,
                () -> DevServicesExtensionJar.find(Map.of(EXTENSION_KEY, unresolved)));

        assertTrue(failure.getMessage().contains(EXTENSION_KEY), failure.getMessage());
    }

    /**
     * The application has neither the extension nor the dev console SPI on its own module path yet (the
     * common case): both jars are resolved and added, extension first.
     */
    @Test
    void resolveAddsTheExtensionAndItsRequiredModulesWhenNeitherIsOnThePath(@TempDir Path tmp) throws Exception {
        File extensionJar = tmp.resolve("extension.jar").toFile();
        File devConsoleJar = tmp.resolve("devconsole-spi.jar").toFile();
        Map<String, Artifact> pluginArtifacts = Map.of(
                EXTENSION_KEY, artifact("io.vidocq.runtime", "vidocq-runtime-devservices-extension", extensionJar),
                DEVCONSOLE_SPI_KEY, artifact("io.vidocq.runtime", "vidocq-runtime-devconsole-spi", devConsoleJar));

        List<Path> resolved = DevServicesExtensionJar.resolve(pluginArtifacts, Set.of());

        assertEquals(List.of(extensionJar.toPath(), devConsoleJar.toPath()), resolved);
    }

    /**
     * The application already depends on the dev console SPI itself (e.g. it implements a panel): its own
     * copy wins, so only the extension jar is added — the module must never appear twice on the path.
     */
    @Test
    void resolveSkipsAJarAlreadyOnTheApplicationsOwnModulePath(@TempDir Path tmp) throws Exception {
        File extensionJar = tmp.resolve("extension.jar").toFile();
        File devConsoleJar = tmp.resolve("devconsole-spi.jar").toFile();
        Map<String, Artifact> pluginArtifacts = Map.of(
                EXTENSION_KEY, artifact("io.vidocq.runtime", "vidocq-runtime-devservices-extension", extensionJar),
                DEVCONSOLE_SPI_KEY, artifact("io.vidocq.runtime", "vidocq-runtime-devconsole-spi", devConsoleJar));
        Set<Artifact> projectArtifacts = Set.of(
                artifact("io.vidocq.runtime", "vidocq-runtime-devconsole-spi", tmp.resolve("app-copy.jar").toFile()));

        List<Path> resolved = DevServicesExtensionJar.resolve(pluginArtifacts, projectArtifacts);

        assertEquals(List.of(extensionJar.toPath()), resolved);
    }

    /** Both already on the path (an unusual but harmless case): nothing left to add. */
    @Test
    void resolveReturnsNothingWhenEverythingIsAlreadyOnThePath(@TempDir Path tmp) throws Exception {
        Map<String, Artifact> pluginArtifacts = Map.of(
                EXTENSION_KEY, artifact("io.vidocq.runtime", "vidocq-runtime-devservices-extension",
                        tmp.resolve("extension.jar").toFile()),
                DEVCONSOLE_SPI_KEY, artifact("io.vidocq.runtime", "vidocq-runtime-devconsole-spi",
                        tmp.resolve("devconsole-spi.jar").toFile()));
        Set<Artifact> projectArtifacts = Set.of(
                artifact("io.vidocq.runtime", "vidocq-runtime-devservices-extension", tmp.resolve("a.jar").toFile()),
                artifact("io.vidocq.runtime", "vidocq-runtime-devconsole-spi", tmp.resolve("b.jar").toFile()));

        assertEquals(List.of(), DevServicesExtensionJar.resolve(pluginArtifacts, projectArtifacts));
    }

    @Test
    void resolveTreatsANullProjectArtifactCollectionAsEmpty(@TempDir Path tmp) throws Exception {
        File extensionJar = tmp.resolve("extension.jar").toFile();
        File devConsoleJar = tmp.resolve("devconsole-spi.jar").toFile();
        Map<String, Artifact> pluginArtifacts = Map.of(
                EXTENSION_KEY, artifact("io.vidocq.runtime", "vidocq-runtime-devservices-extension", extensionJar),
                DEVCONSOLE_SPI_KEY, artifact("io.vidocq.runtime", "vidocq-runtime-devconsole-spi", devConsoleJar));

        List<Path> resolved = DevServicesExtensionJar.resolve(pluginArtifacts, null);

        assertEquals(List.of(extensionJar.toPath(), devConsoleJar.toPath()), resolved);
    }

    @Test
    void resolveThrowsNamingTheDevConsoleSpiWhenMissingFromThePluginsOwnDependencies(@TempDir Path tmp) {
        Map<String, Artifact> pluginArtifacts = Map.of(EXTENSION_KEY,
                artifact("io.vidocq.runtime", "vidocq-runtime-devservices-extension", tmp.resolve("e.jar").toFile()));

        MojoExecutionException failure = assertThrows(MojoExecutionException.class,
                () -> DevServicesExtensionJar.resolve(pluginArtifacts, Set.of()));

        assertTrue(failure.getMessage().contains(DEVCONSOLE_SPI_KEY), failure.getMessage());
    }

    /**
     * Under vidocq:dev the console step runs after this one: the keys added here are recorded so the console's SPI is
     * not put on the path a second time.
     */
    @Test
    void resolveRecordsTheKeysItAdds(@TempDir Path tmp) throws Exception {
        Map<String, Artifact> pluginArtifacts = Map.of(
                EXTENSION_KEY, artifact("io.vidocq.runtime", "vidocq-runtime-devservices-extension",
                        tmp.resolve("extension.jar").toFile()),
                DEVCONSOLE_SPI_KEY, artifact("io.vidocq.runtime", "vidocq-runtime-devconsole-spi",
                        tmp.resolve("devconsole-spi.jar").toFile()));
        Set<String> added = new HashSet<>();

        DevServicesExtensionJar.resolve(pluginArtifacts, Set.of(), added);

        assertEquals(Set.of(EXTENSION_KEY, DEVCONSOLE_SPI_KEY), added);
    }

    private static Artifact artifact(String groupId, String artifactId, File file) {
        Artifact artifact = new DefaultArtifact(groupId, artifactId, "0.4.0-SNAPSHOT", "runtime", "jar", "",
                new DefaultArtifactHandler("jar"));
        artifact.setFile(file);
        return artifact;
    }
}
