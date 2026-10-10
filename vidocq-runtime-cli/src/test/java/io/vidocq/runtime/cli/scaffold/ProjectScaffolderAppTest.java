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

class ProjectScaffolderAppTest {

    @Test
    void mainIsAVidocqRunTrampoline(@TempDir Path dir) throws IOException {
        ProjectScaffolder.scaffold(
                new Command.Create("demo", "com.acme", "com.acme.demo", Set.of(), null), dir);
        String app = Files.readString(dir.resolve("demo/src/main/java/com/acme/demo/DemoApp.java"));

        // Vidocq.run re-resolves the application into the Vauban layer, which promotes
        // META-INF/services to provides; a main that boots in place leaves the generated
        // _VaubanComponents unseen in a jlink image and falls back to reflection.
        assertTrue(app.contains("@VidocqMain"), app);
        assertTrue(app.contains("Vidocq.run(args);"), app);
        assertFalse(app.contains("VidocqBootstrap"), app);
    }
}
