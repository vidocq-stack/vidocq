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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage for {@link RecompileRunner#detectMavenExecutable(Path)} — the only
 * piece we can verify without spinning a real Maven invocation in unit tests.
 *
 * <p>The end-to-end behavior of {@link RecompileRunner#run()} is exercised by
 * the manual smoke test on {@code vidocq-runtime-cassini-rest-example}.</p>
 */
class RecompileRunnerTest {

    @Test
    void detect_prefers_local_wrapper_when_present(@TempDir Path tmp) throws Exception {
        Path wrapper = tmp.resolve("mvnw");
        Files.writeString(wrapper, "#!/bin/sh\nexit 0\n");
        Files.setPosixFilePermissions(wrapper, PosixFilePermissions.fromString("rwxr-xr-x"));

        String resolved = RecompileRunner.detectMavenExecutable(tmp);
        assertEquals(wrapper.toAbsolutePath().toString(), resolved,
                "should prefer ./mvnw when it exists and is executable");
    }

    @Test
    void detect_falls_back_to_path_when_no_wrapper(@TempDir Path tmp) {
        String resolved = RecompileRunner.detectMavenExecutable(tmp);
        // We accept either "mvn" or "mvn.cmd" depending on the host OS.
        assertTrue(resolved.equals("mvn") || resolved.equals("mvn.cmd"),
                "expected PATH fallback, got: " + resolved);
        assertNotEquals(tmp.resolve("mvnw").toString(), resolved);
    }
}
