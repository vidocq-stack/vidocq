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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code vidocq create --extensions crac}: the dependency, and a Dockerfile on a CRaC JDK that takes and restores a checkpoint. */
class ProjectScaffolderCracTest {

    private static Path scaffold(Path dir, Set<String> extensions) throws IOException {
        ProjectScaffolder.scaffold(new ProjectSpec("demo", "com.acme", "com.acme.demo", extensions, null, true),
                dir, "1.2.3", new RecordingReporter());
        return dir.resolve("demo");
    }

    @Test
    void theCracExtensionIsDeclaredAndRequiredByTheModule(@TempDir Path dir) throws IOException {
        Path project = scaffold(dir, Set.of("crac"));

        assertTrue(Files.readString(project.resolve("pom.xml")).contains("<artifactId>vidocq-runtime-crac-extension</artifactId>"));
        assertTrue(Files.readString(project.resolve("src/main/java/module-info.java"))
                .contains("io.vidocq.runtime.extensions.essentials.crac"));
    }

    @Test
    void aDockerfileOnAZuluCracJdkTakesAndRestoresTheCheckpoint(@TempDir Path dir) throws IOException {
        String dockerfile = Files.readString(scaffold(dir, Set.of("crac")).resolve("Dockerfile.crac"));

        assertTrue(dockerfile.contains("crac-jdk"), dockerfile);
        assertTrue(dockerfile.contains("-XX:CRaCCheckpointTo=/crac"), dockerfile);
        assertTrue(dockerfile.contains("-XX:CRaCRestoreFrom=/crac"), dockerfile);
        assertTrue(dockerfile.contains("/app/bin/demo.sh"), dockerfile);
        // A JVM that is process 1 does not attach: --init, the attach listener, and jcmd by main class (not by pid).
        assertTrue(dockerfile.contains("docker run -d --init"), dockerfile);
        assertTrue(dockerfile.contains("-XX:+StartAttachListener"), dockerfile);
        assertTrue(dockerfile.contains("jcmd io.vidocq.runtime.core.Vidocq JDK.checkpoint"), dockerfile);
        assertFalse(dockerfile.contains("jcmd 1 "), dockerfile);
        assertTrue(dockerfile.contains("docker run --rm --init"), dockerfile);
    }

    @Test
    void withoutTheCracExtensionNoDockerfileIsWritten(@TempDir Path dir) throws IOException {
        assertFalse(Files.exists(scaffold(dir, Set.of()).resolve("Dockerfile.crac")));
    }
}
