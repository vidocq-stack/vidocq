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
package io.vidocq.runtime.cli;

import io.vidocq.runtime.cli.build.BuildType;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code vidocq build} and {@code vidocq clean} under {@code --dry-run}: the Maven command, never run. */
class CommandRunnerBuildTest {

    private static String run(Command command, int[] code) {
        var previous = System.out;
        var out = new ByteArrayOutputStream();
        try (var capture = new PrintStream(out, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            code[0] = CommandRunner.run(command);
        } finally {
            System.setOut(previous);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void aJlinkBuildPrintsItsGoals() {
        int[] code = new int[1];
        String out = run(new Command.Build(BuildType.JLINK, false, true, true, List.of("-X")), code);

        assertEquals(0, code[0]);
        assertTrue(out.contains("package vidocq:jlink") && out.contains("-X") && out.contains("dry run"), out);
    }

    /** Whether Docker answers depends on the machine; the goals and the dry run do not. */
    @Test
    void aDockerBuildPrintsItsGoals() {
        int[] code = new int[1];
        String out = run(new Command.Build(BuildType.DOCKER, false, false, true, List.of()), code);

        assertEquals(0, code[0]);
        assertTrue(out.contains("package vidocq:jlink vidocq:docker") && out.contains("dry run"), out);
    }

    @Test
    void aCleanPrintsItsGoal() {
        int[] code = new int[1];
        String out = run(new Command.Clean(false, true, List.of()), code);

        assertEquals(0, code[0]);
        assertTrue(out.contains("clean") && out.contains("dry run"), out);
    }
}
