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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DevServicesExtensionJar#find} resolves the dev services extension jar from {@code
 * ${plugin.artifactMap}}, the way Maven injects this plugin's own resolved dependencies — a {@code
 * groupId:artifactId} keyed map of {@link Artifact}.
 */
class DevServicesExtensionJarTest {

    private static final String KEY = "io.vidocq.runtime:vidocq-runtime-devservices-extension";

    @Test
    void findsTheJarOfTheDeclaredArtifact(@TempDir Path tmp) throws Exception {
        File jar = tmp.resolve("vidocq-runtime-devservices-extension-0.4.0-SNAPSHOT.jar").toFile();
        Artifact artifact = extensionArtifact(jar);

        Path found = DevServicesExtensionJar.find(Map.of(KEY, artifact));

        assertEquals(jar.toPath(), found);
    }

    @Test
    void throwsNamingTheArtifactWhenAbsent() {
        MojoExecutionException failure = assertThrows(MojoExecutionException.class,
                () -> DevServicesExtensionJar.find(Map.of()));

        assertTrue(failure.getMessage().contains(KEY), failure.getMessage());
    }

    @Test
    void throwsWhenTheMapItselfIsMissing() {
        MojoExecutionException failure = assertThrows(MojoExecutionException.class,
                () -> DevServicesExtensionJar.find(null));

        assertTrue(failure.getMessage().contains(KEY), failure.getMessage());
    }

    @Test
    void throwsWhenTheArtifactWasNeverResolvedToAFile() {
        Artifact unresolved = new DefaultArtifact("io.vidocq.runtime", "vidocq-runtime-devservices-extension",
                "0.4.0-SNAPSHOT", "runtime", "jar", "", new DefaultArtifactHandler("jar"));
        // No setFile(...): as if Maven had never resolved it — must not NPE, must name the artifact.

        MojoExecutionException failure = assertThrows(MojoExecutionException.class,
                () -> DevServicesExtensionJar.find(Map.of(KEY, unresolved)));

        assertTrue(failure.getMessage().contains(KEY), failure.getMessage());
    }

    private static Artifact extensionArtifact(File jar) {
        Artifact artifact = new DefaultArtifact("io.vidocq.runtime", "vidocq-runtime-devservices-extension",
                "0.4.0-SNAPSHOT", "runtime", "jar", "", new DefaultArtifactHandler("jar"));
        artifact.setFile(jar);
        return artifact;
    }
}
