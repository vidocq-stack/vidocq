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

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Options that consume the next argument as their value, and the {@code --} passthrough. */
class CliParserOptionValuesTest {

    private static final String START = "start";
    private static final String PORT = "--port";

    @Test
    void startReadsPortConfigAndDebug() {
        Command.Start start = assertInstanceOf(Command.Start.class,
                CliParser.parse(new String[]{START, "-p", "9091", "-c", "app.properties", "--debug"}));
        assertEquals(9091, start.port());
        assertTrue(start.portExplicit());
        assertEquals(Path.of("app.properties"), start.configFile());
        assertTrue(start.debug());
    }

    @Test
    void startLongConfigOption() {
        Command.Start start = (Command.Start) CliParser.parse(new String[]{START, "--config", "x.properties"});
        assertEquals(Path.of("x.properties"), start.configFile());
        assertFalse(start.portExplicit());
    }

    @Test
    void portWithoutValueIsRejected() {
        String[] args = {START, PORT};
        CliException ex = assertThrows(CliException.class, () -> CliParser.parse(args));
        assertEquals("Option --port requires a value.", ex.getMessage());
    }

    @Test
    void nonNumericPortIsRejected() {
        String[] args = {"dev", "-p", "abc"};
        CliException ex = assertThrows(CliException.class, () -> CliParser.parse(args));
        assertEquals("Option --port expects an integer, got: abc", ex.getMessage());
    }

    @Test
    void valueOptionConsumesTheNextArgumentEvenWhenItLooksLikeAFlag() {
        Command.Dev dev = (Command.Dev) CliParser.parse(new String[]{"dev", "-P", "--debug"});
        assertEquals("--debug", dev.profile());
        assertFalse(dev.debug());
    }

    @Test
    void unknownStartOptionIsRejected() {
        String[] args = {START, "--nope"};
        CliException ex = assertThrows(CliException.class, () -> CliParser.parse(args));
        assertEquals("Unknown option '--nope' for 'start'. Run 'vidocq help start'.", ex.getMessage());
    }

    @Test
    void createReadsEveryValueOption() {
        Command.Create create = assertInstanceOf(Command.Create.class, CliParser.parse(new String[]{
                "create", "-n", "demo", "-g", "org.acme", "--package", "org.acme.demo",
                "-x", "cassini-rest", "--extension", "knock-health", "--parent-version", "1.2.3", "--main"}));
        assertEquals("demo", create.spec().name());
        assertEquals("org.acme", create.spec().groupId());
        assertEquals("org.acme.demo", create.spec().pkg());
        assertEquals(Set.of("cassini-rest", "knock-health"), Set.copyOf(create.spec().extensions()));
    }

    @Test
    void createValueOptionWithoutValueIsRejected() {
        String[] args = {"create", "--group-id"};
        CliException ex = assertThrows(CliException.class, () -> CliParser.parse(args));
        assertEquals("Option --group-id requires a value.", ex.getMessage());
    }

    @Test
    void cleanPassesEverythingAfterDoubleDashThrough() {
        Command.Clean clean = assertInstanceOf(Command.Clean.class,
                CliParser.parse(new String[]{"clean", "-o", "--", "--dry-run", "-X"}));
        assertTrue(clean.offline());
        assertFalse(clean.dryRun());
        assertEquals(List.of("--dry-run", "-X"), clean.passthrough());
    }

    @Test
    void cleanWithEmptyPassthrough() {
        Command.Clean clean = (Command.Clean) CliParser.parse(new String[]{"clean", "--dry-run", "--"});
        assertTrue(clean.dryRun());
        assertEquals(List.of(), clean.passthrough());
    }

    @Test
    void unknownUpdateOptionIsRejected() {
        String[] args = {"update", "--check", "--now"};
        CliException ex = assertThrows(CliException.class, () -> CliParser.parse(args));
        assertEquals("Unknown option '--now' for 'update'. Run 'vidocq help update'.", ex.getMessage());
    }

    @Test
    void anUnknownCommandIsAPluginWithTheRestOfTheArguments() {
        Command.Plugin plugin = assertInstanceOf(Command.Plugin.class,
                CliParser.parse(new String[]{"deploy", "--to", "prod"}));
        assertEquals("deploy", plugin.name());
        assertEquals(List.of("--to", "prod"), plugin.args());
    }

    @Test
    void buildPassthroughStopsOptionParsing() {
        Command.Build build = (Command.Build) CliParser.parse(new String[]{"build", "jlink", "--", "--bogus"});
        assertEquals(List.of("--bogus"), build.passthrough());
        assertFalse(build.offline());
    }
}
