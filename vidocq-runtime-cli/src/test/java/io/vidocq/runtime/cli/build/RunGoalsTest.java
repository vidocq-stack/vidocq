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
package io.vidocq.runtime.cli.build;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RunGoalsTest {

    @Test
    void devCompilesThenRunsTheDevGoalWithItsProfile() {
        assertEquals(List.of("process-classes", "vidocq:dev", "-Dvidocq.profile=dev"),
                RunGoals.dev("dev", false, 8080, false));
    }

    @Test
    void devPassesAnExplicitPortToTheApplicationAndTheDebugSwitch() {
        assertEquals(List.of("process-classes", "vidocq:dev", "-Dvidocq.profile=staging",
                        "-Dvidocq.dev.systemProperties=vidocq.chappe.listener.default.port=9090", "-Dvidocq.dev.debug=true"),
                RunGoals.dev("staging", true, 9090, true));
    }

    @Test
    void startRunsTheApplicationGoal() {
        assertEquals(List.of("vidocq:run"), RunGoals.start(false, 8080, null, false));
    }

    @Test
    void startPassesPortAndConfigFileTogetherAndTheDebugSwitch() {
        Path config = Path.of("/etc/app/vidocq.properties");

        assertEquals(List.of("vidocq:run",
                        "-Dvidocq.run.systemProperties=vidocq.chappe.listener.default.port=9090,vidocq.config.file=" + config,
                        "-Dvidocq.run.debug=true"),
                RunGoals.start(true, 9090, config, true));
    }
}
