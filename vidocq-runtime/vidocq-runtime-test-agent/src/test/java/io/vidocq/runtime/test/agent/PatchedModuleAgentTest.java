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
package io.vidocq.runtime.test.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vidocq.runtime.test.agent.IdeLaunch.Result;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.spi.ToolProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Starts real JVMs, with the launch an IDE builds for a test patched into a module, with and without the agent. */
class PatchedModuleAgentTest {

    @TempDir
    static Path tmp;

    static IdeLaunch launch;
    static Path agentJar;

    @BeforeAll
    static void compileTheFixture() throws Exception {
        launch = new IdeLaunch(tmp);
        agentJar = agentJar(tmp.resolve("agent.jar"));
    }

    /**
     * The agent jar: Surefire runs before {@code package}, so the test packs the compiled agent itself, with the
     * {@code Premain-Class} the module's own jar carries.
     */
    private static Path agentJar(Path jar) throws Exception {
        Path classes = Path.of(PatchedModuleAgent.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path manifest = Files.writeString(jar.resolveSibling("MANIFEST.MF"),
                "Premain-Class: " + PatchedModuleAgent.class.getName() + "\n");
        ToolProvider tool = ToolProvider.findFirst("jar").orElseThrow();
        if (tool.run(System.out, System.err, "--create", "--file", jar.toString(), "--manifest", manifest.toString(),
                "-C", classes.toString(), ".") != 0) {
            throw new IllegalStateException("The agent does not package: " + jar);
        }
        return jar;
    }

    @Test
    void without_the_agent_the_ide_launch_fails_on_the_opens() throws Exception {
        Result result = launch.run(launch.ideCommand());

        assertNotEquals(0, result.exit());
        assertTrue(result.err().contains("module app does not \"opens app.internal\" to module platform"),
                result.err());
    }

    @Test
    void without_the_agent_it_then_fails_on_the_reads() throws Exception {
        Result result = launch.run(launch.ideCommand("--add-opens", "app/app.internal=platform"));

        assertNotEquals(0, result.exit());
        assertTrue(result.err().contains("module app does not read module api"), result.err());
    }

    @Test
    void with_the_agent_the_test_runs_on_the_module_path() throws Exception {
        Result result = launch.run(launch.ideCommand("-javaagent:" + agentJar));

        assertEquals("ran app.internal.AppTest in module app, platform in module platform", result.out().strip());
        assertEquals(0, result.exit());
        assertEquals("", result.err());
    }

    /** {@code platform} names a friend, {@code params}, that this launch does not hold: nothing is said of it. */
    @Test
    void verbose_says_what_changed_and_leaves_out_a_module_the_jvm_did_not_resolve() throws Exception {
        Result result = launch.run(launch.ideCommand("-javaagent:" + agentJar + "=verbose"));

        assertEquals("[vidocq] app: reads api, engine, platform; opens app.internal to api, engine, platform",
                result.err().strip());
    }

    @Test
    void opens_the_test_packages_to_a_test_library_the_module_requires_static() throws Exception {
        IdeLaunch optional = new IdeLaunch(tmp.resolve("static"),
                "module app { requires lib; requires static platform; }");

        Result without = optional.run(optional.ideCommand());
        Result with = optional.run(optional.ideCommand("-javaagent:" + agentJar + "=verbose"));

        assertTrue(without.err().contains("module app does not \"opens app.internal\" to module platform"),
                without.err());
        assertEquals(0, with.exit(), with.err());
        assertEquals("[vidocq] app: reads api, engine; opens app.internal to api, engine, platform",
                with.err().strip());
    }

    @Test
    void changes_nothing_when_the_launch_is_already_complete() throws Exception {
        Result result = launch.run(launch.ideCommand("-javaagent:" + agentJar + "=verbose",
                "--add-reads", "app=api,engine,platform",
                "--add-opens", "app/app.internal=api,engine,platform"));

        assertEquals(0, result.exit());
        assertEquals("", result.err());
    }

    /** The launch Maven builds. The module under test does not read {@code dep}, and that stays so. */
    @Test
    void changes_nothing_when_the_test_libraries_are_on_the_class_path() throws Exception {
        Result result = launch.run(launch.classPathCommand("-javaagent:" + agentJar + "=verbose"));

        assertEquals(0, result.exit());
        assertEquals("", result.err());
    }

    @Test
    void changes_nothing_when_no_module_reads_the_class_path() throws Exception {
        Result result = launch.run(launch.plainCommand("-javaagent:" + agentJar + "=verbose"));

        assertEquals(0, result.exit());
        assertEquals("", result.err());
    }

    /** {@code platform} exports a package to {@code params} alone, and the IDE left {@code params} on the class path. */
    @Test
    void without_the_agent_a_friend_left_on_the_class_path_is_refused() throws Exception {
        Result result = launch.run(launch.friendCommand(launch.friendJar(), "app.internal.AppFriendTest",
                "--add-reads", "app=api,engine,platform", "--add-opens", "app/app.internal=api,engine,platform"));

        assertNotEquals(0, result.exit());
        assertTrue(result.err().contains("module platform does not export internal to unnamed module"),
                result.err());
    }

    @Test
    void with_the_agent_a_friend_left_on_the_class_path_gets_what_is_exported_to_it() throws Exception {
        Result result = launch.run(launch.friendCommand(launch.friendJar(), "app.internal.AppFriendTest",
                "-javaagent:" + agentJar));

        assertEquals(0, result.exit(), result.err());
        assertEquals("ran app.internal.AppFriendTest in module app, platform in module platform",
                result.out().strip());
    }

    @Test
    void with_the_agent_a_friend_left_on_the_class_path_gets_what_is_opened_to_it() throws Exception {
        Result result = launch.run(launch.friendCommand(launch.friendJar(), "app.internal.AppDeepFriendTest",
                "-javaagent:" + agentJar));

        assertEquals(0, result.exit(), result.err());
        assertEquals("ran app.internal.AppDeepFriendTest in module app, platform in module platform",
                result.out().strip());
    }

    @Test
    void a_friend_is_also_a_folder_of_classes_that_holds_its_descriptor() throws Exception {
        Result result = launch.run(launch.friendCommand(launch.friendClasses(), "app.internal.AppFriendTest",
                "-javaagent:" + agentJar));

        assertEquals(0, result.exit(), result.err());
    }

    @Test
    void verbose_names_the_friend_the_class_path_stands_for() throws Exception {
        Result result = launch.run(launch.friendCommand(launch.friendJar(), "app.internal.AppFriendTest",
                "-javaagent:" + agentJar + "=verbose"));

        assertEquals(List.of(
                        "[vidocq] app: reads api, engine, platform; opens app.internal to api, engine, platform",
                        "[vidocq] platform: exports internal to the class path for params;"
                                + " opens hidden to the class path for params"),
                result.err().strip().lines().toList());
    }

    @Test
    void the_agent_loads_from_a_path_with_a_space() throws Exception {
        Path copy = Files.copy(agentJar, Files.createDirectories(tmp.resolve("with space")).resolve("agent.jar"));

        Result result = launch.run(launch.ideCommand("-javaagent:" + copy));

        assertEquals(0, result.exit(), result.err());
    }
}
