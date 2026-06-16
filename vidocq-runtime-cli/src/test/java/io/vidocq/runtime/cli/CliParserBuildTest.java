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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliParserBuildTest {

    @Test
    void bareBuildIsPackage() {
        Command.Build b = assertInstanceOf(Command.Build.class,
                CliParser.parse(new String[]{"build"}));
        assertEquals(BuildType.PACKAGE, b.type());
        assertFalse(b.offline());
        assertFalse(b.skipTests());
        assertFalse(b.dryRun());
        assertTrue(b.passthrough().isEmpty());
    }

    @Test
    void buildTypeToken() {
        Command.Build b = assertInstanceOf(Command.Build.class,
                CliParser.parse(new String[]{"build", "jlink"}));
        assertEquals(BuildType.JLINK, b.type());
    }

    @Test
    void buildFlags() {
        Command.Build b = assertInstanceOf(Command.Build.class,
                CliParser.parse(new String[]{"build", "docker", "-o", "--skip-tests", "--dry-run"}));
        assertEquals(BuildType.DOCKER, b.type());
        assertTrue(b.offline());
        assertTrue(b.skipTests());
        assertTrue(b.dryRun());
    }

    @Test
    void buildPassthroughAfterDoubleDash() {
        Command.Build b = assertInstanceOf(Command.Build.class,
                CliParser.parse(new String[]{"build", "--", "-X", "-Dfoo=bar"}));
        assertEquals(List.of("-X", "-Dfoo=bar"), b.passthrough());
    }

    @Test
    void unknownBuildTypeIsCliException() {
        CliException ex = assertThrows(CliException.class,
                () -> CliParser.parse(new String[]{"build", "native"}));
        assertTrue(ex.getMessage().contains("native"));
    }

    @Test
    void unknownBuildOption() {
        assertThrows(CliException.class,
                () -> CliParser.parse(new String[]{"build", "--nope"}));
    }

    @Test
    void bareClean() {
        Command.Clean c = assertInstanceOf(Command.Clean.class,
                CliParser.parse(new String[]{"clean"}));
        assertFalse(c.offline());
        assertFalse(c.dryRun());
        assertTrue(c.passthrough().isEmpty());
    }

    @Test
    void cleanFlagsAndPassthrough() {
        Command.Clean c = assertInstanceOf(Command.Clean.class,
                CliParser.parse(new String[]{"clean", "-o", "--dry-run", "--", "-q"}));
        assertTrue(c.offline());
        assertTrue(c.dryRun());
        assertEquals(List.of("-q"), c.passthrough());
    }

    @Test
    void unknownCleanOption() {
        assertThrows(CliException.class,
                () -> CliParser.parse(new String[]{"clean", "--nope"}));
    }
}
