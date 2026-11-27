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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Vidocq/vidocq#799, BUG-20261127-09: what {@code vidocq extension add} does to a running {@code vidocq:dev}. The pom
 * gains a dependency and {@code module-info.java} a {@code requires} of its module, which the module path resolved at
 * the start of the session lacks: the session restarts on the new dependencies, with one line that says why, instead of
 * the application dying on a {@code FindException}.
 */
@DisabledOnOs(OS.WINDOWS)
class DependencyChangeDevGoalIT {

    private static final String STARTED = "Vidocq - Started in";
    private static final String RESTART = "pom.xml changed the dependencies (added jakarta.json-api): restarting the"
            + " application on the new ones.";

    @Test
    void aDependencyAddedWhileVidocqDevRunsRestartsTheSessionOnIt(@TempDir Path dir) throws Exception {
        Path project = Fixture.copy(dir);
        Path logs = Fixture.logs();
        Fixture.warmUp(project, logs.resolve("dev-dependency-warm-up.log"));
        Path log = logs.resolve("vidocq-dev-dependency.log");

        Process mvn = Fixture.start(project, log, "vidocq:dev", "-Dvidocq.dev.debug=false",
                "-Dvidocq.chappe.listener.default.port=0", "-Dvidocq.devconsole.port=0");
        try {
            Fixture.awaitLog(mvn, log, STARTED);

            // What vidocq extension add writes: the dependency first, then the requires of its module.
            Path pom = project.resolve("pom.xml");
            String junit = "<dependency>\n            <groupId>org.junit.jupiter</groupId>";
            String pomText = Files.readString(pom);
            assertTrue(pomText.contains(junit), pomText);
            Files.writeString(pom, pomText.replace(junit, "<dependency>\n"
                    + "            <groupId>jakarta.json</groupId>\n"
                    + "            <artifactId>jakarta.json-api</artifactId>\n"
                    + "            <version>2.1.3</version>\n"
                    + "        </dependency>\n"
                    + "        " + junit));
            Path moduleInfo = project.resolve("src/main/java/module-info.java");
            Files.writeString(moduleInfo, Files.readString(moduleInfo).replace("requires io.vidocq.runtime.core;",
                    "requires io.vidocq.runtime.core;\n    requires jakarta.json;"));

            Fixture.awaitLog(mvn, log, RESTART);
            Fixture.poll(Fixture.TIMEOUT, () -> Fixture.occurrences(log, STARTED), started -> started >= 2);
            String said = Fixture.read(log);
            assertEquals(2, Fixture.occurrences(log, STARTED), "the application started again:\n" + said);
            assertEquals(1, Fixture.occurrences(log, RESTART), "one line says why:\n" + said);
            assertFalse(said.contains("FindException"), "no module is missing:\n" + said);
            assertFalse(said.contains("Cannot create the application layer"), said);
            assertTrue(mvn.isAlive(), "vidocq:dev still runs:\n" + said);

            // The new session reloads as the first one did.
            int before = said.length();
            Fixture.addGreeterMethod(project, "afterTheRestart", null);
            String after = Fixture.poll(Duration.ofMinutes(2), () -> Fixture.read(log).substring(before),
                    text -> text.contains("Greeter gained a method afterTheRestart"));
            assertTrue(after.contains("Greeter gained a method afterTheRestart"),
                    "the restarted session reloads a change:\n" + after);
        } finally {
            Fixture.stop(mvn);
        }
    }
}
