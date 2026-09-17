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
package io.vidocq.runtime.core.console;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The console logging seen from outside a real JVM: which stream receives the records, how many
 * lines each one takes, and the colour policy with a stand-in IntelliJ agent while stdout is not
 * a terminal (it is redirected to a file here).
 */
class ConsoleLoggingProcessTest {

    private static final Pattern RECORD_LINE = Pattern.compile(
            "^\\[(ERROR|WARN |INFO )]\\[\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}]\\[.{15}]\\[.{45}] : .+$");

    @TempDir
    Path dir;

    private record Run(int exitCode, String stdout, String stderr) {}

    @Test
    void everyRecordIsOneLineOnStdoutAndNothingGoesToStderr() throws Exception {
        Run run = child(List.of(), Map.of());

        assertEquals(0, run.exitCode(), run.stderr());
        assertEquals("", run.stderr());
        List<String> lines = run.stdout().lines().toList();
        List<String> records = lines.stream().filter(l -> l.startsWith("[")).toList();
        assertEquals(ConsoleLoggingChildMain.RECORDS, records.size(), run.stdout());
        for (String record : records) {
            assertTrue(RECORD_LINE.matcher(record).matches(), "not one aligned record line: " + record);
        }
        for (String other : lines) {
            if (!other.startsWith("[")) {
                assertTrue(other.startsWith("\t") || other.startsWith("java.lang.IllegalStateException: "),
                        "only the stack trace follows a record: " + other);
            }
        }
        assertFalse(run.stdout().contains(""), "no colour when stdout is not a terminal");
        String source = pad("i.v.r.c.c.ConsoleLoggingChildMain#main", 45);
        assertTrue(run.stdout().contains("[INFO ][") && run.stdout().contains(
                "][" + pad("main", 15) + "][" + source + "] : Vidocq - Configuration phase"), run.stdout());
        // i.v.r.c.c.ConsoleLoggingChildMain#lambda$main$0 is 47 columns: the packages go
        assertTrue(Pattern.compile("]\\[virtual-\\d+ *]\\[ConsoleLoggingChildMain#lambda\\$main\\$0 *]"
                + " : Logged from a virtual thread").matcher(run.stdout()).find(), run.stdout());
        assertTrue(run.stdout().contains("][" + source + "] : Logged through java.util.logging"), run.stdout());
    }

    @Test
    void intellijsRunConsoleGetsColoursWithoutATerminal() throws Exception {
        Run run = child(List.of("-javaagent:" + standInIdeaAgent()), Map.of());

        assertEquals(0, run.exitCode(), run.stderr());
        assertEquals("", run.stderr());
        assertTrue(run.stdout().contains("[[32mINFO[0m ]["), run.stdout());
        assertTrue(run.stdout().contains("[[1;31mERROR[0m]["), run.stdout());
    }

    @Test
    void noColorWinsOverAlways() throws Exception {
        Run run = child(List.of("-javaagent:" + standInIdeaAgent(), "-Dvidocq.console.color=always"),
                Map.of("NO_COLOR", "1"));

        assertEquals(0, run.exitCode(), run.stderr());
        assertEquals("", run.stderr());
        assertEquals(ConsoleLoggingChildMain.RECORDS, run.stdout().lines().filter(l -> l.startsWith("[")).count());
        assertFalse(run.stdout().contains(""), run.stdout());
    }

    @Test
    void aLoggingConfigurationFileKeepsTheJdkOutput() throws Exception {
        Path config = Files.writeString(dir.resolve("logging.properties"),
                "handlers = java.util.logging.ConsoleHandler\n.level = INFO\n");

        Run run = child(List.of("-Djava.util.logging.config.file=" + config), Map.of());

        assertEquals(0, run.exitCode(), run.stderr());
        assertEquals("", run.stdout());
        assertTrue(run.stderr().contains("Vidocq - Configuration phase"), run.stderr());
    }

    private Run child(List<String> jvmOptions, Map<String, String> environment)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.addAll(jvmOptions);
        command.add("-cp");
        command.add(childClassPath());
        command.add(ConsoleLoggingChildMain.class.getName());

        ProcessBuilder builder = new ProcessBuilder(command);
        Map<String, String> env = builder.environment();
        for (String inherited : List.of("NO_COLOR", "TERM", "VIDOCQ_LOG_CONSOLE", "VIDOCQ_CONSOLE_COLOR",
                "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")) {
            env.remove(inherited);
        }
        env.putAll(environment);
        Path out = dir.resolve("stdout-" + System.nanoTime() + ".txt");
        Path err = dir.resolve("stderr-" + System.nanoTime() + ".txt");
        builder.redirectOutput(out.toFile()).redirectError(err.toFile());

        Process process = builder.start();
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("child JVM did not finish within 60 s");
        }
        return new Run(process.exitValue(), Files.readString(out, StandardCharsets.UTF_8),
                Files.readString(err, StandardCharsets.UTF_8));
    }

    /** This module's classes and test classes, and every dependency Surefire resolved, on a class path. */
    private static String childClassPath() {
        Path basedir = Path.of(System.getProperty("basedir", System.getProperty("user.dir")));
        Set<String> entries = new LinkedHashSet<>();
        entries.add(basedir.resolve("target/test-classes").toString());
        entries.add(basedir.resolve("target/classes").toString());
        for (String property : List.of("jdk.module.path", "java.class.path")) {
            String value = System.getProperty(property);
            if (value != null && !value.isBlank()) {
                entries.addAll(List.of(value.split(Pattern.quote(File.pathSeparator))));
            }
        }
        return String.join(File.pathSeparator, entries);
    }

    /** A jar named {@code idea_rt.jar} whose agent does nothing. */
    private Path standInIdeaAgent() throws IOException {
        Path jar = dir.resolve("idea_rt.jar");
        if (Files.exists(jar)) {
            return jar;
        }
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(new Attributes.Name("Premain-Class"), StandInIdeaAgent.class.getName());
        try (OutputStream file = Files.newOutputStream(jar); JarOutputStream ignored = new JarOutputStream(file, manifest)) {
            // the manifest is the whole jar: the agent class comes from the class path
        }
        return jar;
    }

    private static String pad(String value, int width) {
        return value + " ".repeat(width - value.length());
    }
}
