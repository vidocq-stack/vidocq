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
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke coverage of {@link ChildJvm}. We can't easily spin a real Vidocq
 * module path from a unit test, but we can verify that the process lifecycle
 * (start → isAlive → stop) behaves correctly against a trivial JVM that just
 * prints {@code -version} and exits.
 */
class ChildJvmTest {

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void start_then_stop_completes_within_grace(@TempDir Path tmp) throws Exception {
        // --module-path emptyDir --module unknown will fail-fast at the JVM
        // level, but the lifecycle (start → exit) still applies and is what
        // we want to exercise here.
        ChildJvm jvm = ChildJvm.of(
                List.of(tmp),
                List.of(),
                "no.such.module",
                null,
                List.of("-Xshare:off"),
                Map.of("vidocq.profile", "dev"),
                tmp);

        long pid = jvm.start();
        assertTrue(pid > 0, "spawned PID must be positive");

        // The child will exit on its own (unknown module) — give it time, then
        // call stop() and verify isAlive() flips to false.
        Thread.sleep(1500);
        jvm.stop(Duration.ofSeconds(5));
        assertFalse(jvm.isAlive(), "child JVM should have exited");
    }

    @Test
    void layer_mode_boots_the_runtime_and_hands_the_app_over_via_properties(@TempDir Path tmp) {
        ChildJvm jvm = ChildJvm.of(
                List.of(tmp.resolve("libs")),
                List.of(tmp.resolve("classes")),
                "com.example.app",
                "com.example.app.Main",
                List.of(),
                Map.of("vidocq.profile", "dev"),
                tmp);

        var command = jvm.command();
        assertTrue(command.contains("-Dvidocq.app.path=" + tmp.resolve("classes")),
                "app classes travel through vidocq.app.path: " + command);
        assertTrue(command.contains("-Dvidocq.app.main=com.example.app.Main"),
                "the app main runs through the layer: " + command);
        assertEquals("io.vidocq.runtime.core/io.vidocq.runtime.core.Vidocq",
                command.get(command.size() - 1),
                "the root module is always the runtime in layer mode");
        int mp = command.indexOf("--module-path");
        assertEquals(tmp.resolve("libs").toString(), command.get(mp + 1),
                "the module path carries only the dependencies");
    }

    @Test
    void legacy_mode_keeps_the_module_main_launch(@TempDir Path tmp) {
        ChildJvm jvm = ChildJvm.of(
                List.of(tmp.resolve("classes"), tmp.resolve("libs")),
                List.of(),
                "com.example.app",
                "com.example.app.Main",
                List.of(),
                Map.of(),
                tmp);

        var command = jvm.command();
        assertEquals("com.example.app/com.example.app.Main", command.get(command.size() - 1));
        assertTrue(command.stream().noneMatch(a -> a.startsWith("-Dvidocq.app.path=")),
                "no layer hand-over in legacy mode: " + command);
    }
}
