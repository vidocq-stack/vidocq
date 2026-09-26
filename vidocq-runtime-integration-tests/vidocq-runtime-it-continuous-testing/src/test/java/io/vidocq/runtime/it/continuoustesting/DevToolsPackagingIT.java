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
package io.vidocq.runtime.it.continuoustesting;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Vidocq/vidocq#143: a project that declares the dev console at compile scope still gets a distribution without it
 * — {@code vidocq:package} drops every dev-only jar, even when the project asks for it, and logs a WARNING once. The
 * console's SPI, an ordinary API jar that an all-in-one extension may require, is kept.
 */
@DisabledOnOs(OS.WINDOWS)
class DevToolsPackagingIT {

    @Test
    void aDeclaredConsoleIsNeverPackaged(@TempDir Path dir) throws Exception {
        Path project = Fixture.copy(dir);
        Fixture.addDependency(project, "io.vidocq.runtime.extensions.essentials",
                "vidocq-runtime-devconsole-extension", "compile");
        Path log = Fixture.logs().resolve("package.log");

        Process mvn = Fixture.start(project, log, "package", "vidocq:package", "-DskipTests");
        assertEquals(0, mvn.waitFor(), Fixture.read(log));

        assertTrue(Fixture.read(log).contains("vidocq-runtime-devconsole-extension is dev-only: not packaged"));
        try (var zip = new ZipFile(Fixture.distZip(project).toFile())) {
            assertTrue(zip.stream().noneMatch(e -> e.getName().contains("devconsole-extension")),
                    "no dev console in the zip");
            assertTrue(zip.stream().anyMatch(e -> e.getName().contains("vidocq-runtime-devconsole-spi")),
                    "the console SPI ships: a module that requires it must resolve in the binary");
        }
    }
}
