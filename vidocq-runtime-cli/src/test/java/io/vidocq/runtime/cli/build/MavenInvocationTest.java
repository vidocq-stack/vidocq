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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MavenInvocationTest {

    @Test
    void plainPackage() {
        List<String> cmd = MavenInvocation.command(
                "./mvnw", BuildType.PACKAGE.goals(), MavenInvocation.Options.none());
        assertEquals(List.of("./mvnw", "package"), cmd);
    }

    @Test
    void offlinePrependsFlagBeforeGoals() {
        List<String> cmd = MavenInvocation.command(
                "mvn", List.of("package"),
                new MavenInvocation.Options(true, false, List.of()));
        assertEquals(List.of("mvn", "-o", "package"), cmd);
    }

    @Test
    void skipTestsAddsSystemProperty() {
        List<String> cmd = MavenInvocation.command(
                "mvn", List.of("package"),
                new MavenInvocation.Options(false, true, List.of()));
        assertEquals(List.of("mvn", "-DskipTests", "package"), cmd);
    }

    @Test
    void jlinkWithAllFlagsAndPassthrough() {
        List<String> cmd = MavenInvocation.command(
                "/abs/mvnw", BuildType.JLINK.goals(),
                new MavenInvocation.Options(true, true, List.of("-X", "-Dfoo=bar")));
        assertEquals(List.of(
                "/abs/mvnw", "-o", "-DskipTests",
                "package", "vidocq:jlink",
                "-X", "-Dfoo=bar"), cmd);
    }

    @Test
    void cleanGoal() {
        List<String> cmd = MavenInvocation.command(
                "mvn", List.of("clean"), MavenInvocation.Options.none());
        assertEquals(List.of("mvn", "clean"), cmd);
    }

    @Test
    void blankExecutableRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> MavenInvocation.command("  ", List.of("package"), MavenInvocation.Options.none()));
    }

    @Test
    void emptyGoalsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> MavenInvocation.command("mvn", List.of(), MavenInvocation.Options.none()));
    }

    @Test
    void resultIsImmutable() {
        List<String> cmd = MavenInvocation.command(
                "mvn", List.of("package"), MavenInvocation.Options.none());
        assertThrows(UnsupportedOperationException.class, () -> cmd.add("x"));
    }

    @Test
    void passthroughDefensivelyCopied() {
        var passthrough = new java.util.ArrayList<>(List.of("-X"));
        MavenInvocation.Options opts = new MavenInvocation.Options(false, false, passthrough);
        passthrough.add("-mutated");
        assertEquals(List.of("-X"), opts.passthrough());
    }
}
