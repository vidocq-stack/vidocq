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
package io.vidocq.runtime.core;

import io.vidocq.runtime.core.report.StartupAnomalies;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.spi.ToolProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A {@code Vidocq.run} trampoline launched as an IDE or a legacy launcher does: the application module on the module
 * path, so in the boot layer. The application provides a service and counts the providers of it that the context
 * loader finds: one per copy of its module (BUG-20261114-10).
 */
@DisplayName("BUG-20261114-10 — a trampoline launched from the module path")
class TrampolineLaunchProcessTest {

    private static final String APP_LOADER = "jdk.internal.loader.ClassLoaders$AppClassLoader";

    @TempDir
    Path dir;

    private Path application;

    @BeforeEach
    void compileTheApplication() throws Exception {
        application = compile();
    }

    @Test
    @DisplayName("vidocq.app.layer.off: the trampoline runs the application from the boot layer, loaded once")
    void withTheLayerOffTheApplicationIsTheBootModule() throws Exception {
        Run run = launch("-D" + LayerSplit.OFF_PROPERTY + "=true");

        assertEquals(0, run.exitCode(), run.output());
        assertTrue(run.output().contains("TOOL-PROVIDERS 1 " + APP_LOADER), run.output());
        assertFalse(run.output().contains("Application layer ready"), run.output());
        assertFalse(run.output().contains("[" + StartupAnomalies.MODULES_TWICE + "]"), run.output());
    }

    @Test
    @DisplayName("an IDE run: the trampoline loads the application twice, and the start warns with the modules")
    void anIdeRunWarnsOfTheModulesLoadedTwice() throws Exception {
        Run run = launch();

        assertEquals(0, run.exitCode(), run.output());
        assertTrue(run.output().contains("TOOL-PROVIDERS 2 io.vidocq.vauban.classloader.VaubanClassLoader"),
                run.output());
        assertTrue(run.output().contains("[" + StartupAnomalies.MODULES_TWICE + "] 1 application module is in the"
                + " boot layer and in the application layer: acme.app."), run.output());
        assertTrue(run.output().contains("Run the application through vidocq:dev or vidocq:run"), run.output());
    }

    // ---------------------------------------------------------------- fixtures

    private record Run(int exitCode, String output) {}

    /** {@code acme.app}: a trampoline main, the application it boots, and the service it provides and counts. */
    private Path compile() throws Exception {
        Path sources = dir.resolve("src");
        write(sources.resolve("module-info.java"), """
                module acme.app {
                    requires io.vidocq.runtime.core;
                    exports acme.app;
                    uses java.util.spi.ToolProvider;
                    provides java.util.spi.ToolProvider with acme.app.AppTool;
                }
                """);
        write(sources.resolve("acme/app/Main.java"), """
                package acme.app;

                public final class Main {
                    private Main() {}

                    public static void main(String[] args) {
                        System.exit(io.vidocq.runtime.core.Vidocq.run(Probe.class, args));
                    }
                }
                """);
        write(sources.resolve("acme/app/Probe.java"), """
                package acme.app;

                import java.util.ServiceLoader;
                import java.util.spi.ToolProvider;

                public final class Probe implements io.vidocq.runtime.spi.VidocqApp {
                    @Override
                    public int run(String... args) {
                        long tools = ServiceLoader.load(ToolProvider.class).stream()
                                .filter(p -> p.type().getName().equals("acme.app.AppTool")).count();
                        System.out.println("TOOL-PROVIDERS " + tools + " "
                                + Probe.class.getClassLoader().getClass().getName());
                        return 0;
                    }
                }
                """);
        write(sources.resolve("acme/app/AppTool.java"), """
                package acme.app;

                import java.io.PrintWriter;

                public final class AppTool implements java.util.spi.ToolProvider {
                    @Override
                    public String name() {
                        return "acme-app-tool";
                    }

                    @Override
                    public int run(PrintWriter out, PrintWriter err, String... args) {
                        return 0;
                    }
                }
                """);
        Path classes = dir.resolve("acme.app");
        List<String> args = new ArrayList<>(List.of("--module-path", modulePath(), "-d", classes.toString()));
        try (var files = Files.walk(sources)) {
            files.filter(p -> p.toString().endsWith(".java")).forEach(p -> args.add(p.toString()));
        }
        StringWriter messages = new StringWriter();
        ToolProvider javac = ToolProvider.findFirst("javac").orElseThrow();
        int code = javac.run(new PrintWriter(messages), new PrintWriter(messages), args.toArray(String[]::new));
        assertEquals(0, code, messages.toString());
        return classes;
    }

    private static void write(Path file, String content) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    /** {@code java -p <runtime>:<application> -m acme.app/acme.app.Main}, as an IDE runs a main class. */
    private Run launch(String... properties) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-Dvidocq.banner.mode=off");
        command.add("-Dvidocq.app.layer=classes");
        command.addAll(List.of(properties));
        command.add("--module-path");
        command.add(modulePath() + File.pathSeparator + application);
        command.add("--module");
        command.add("acme.app/acme.app.Main");
        Path output = dir.resolve("output.txt");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile())
                .start();
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("the application did not end within 60 s:\n" + Files.readString(output, StandardCharsets.UTF_8));
        }
        return new Run(process.exitValue(), Files.readString(output, StandardCharsets.UTF_8));
    }

    /** The runtime and its dependencies, as the tests of this module run on them. */
    private static String modulePath() {
        String modulePath = System.getProperty("jdk.module.path", "");
        assertFalse(modulePath.isBlank(), "the tests of this module run on the module path");
        return modulePath;
    }
}
