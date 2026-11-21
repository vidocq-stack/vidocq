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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.spi.ToolProvider;

/**
 * A small set of modules and the launches an IDE builds over them, to start real JVMs from a test.
 *
 * <p>It mirrors the launch IntelliJ IDEA 2026.3 builds for a JUnit test of a module that has a
 * {@code module-info.java}: {@code app} is the module under test and {@code app.internal.AppTest} the test patched
 * into it; {@code platform} stands for the JUnit platform, which reaches the test by reflection; {@code api} for
 * the JUnit API the test calls; {@code engine} for an engine the platform finds as a service; {@code unused} for a
 * module that is on the module path and that the JVM does not resolve; {@code starter.Main}, on the class path,
 * for the IDE's own test starter; {@code dep} for a dependency of a dependency, which the module under test does
 * not read and has no reason to; {@code params} for a test library that the platform names as a friend, in an
 * {@code exports … to} and an {@code opens … to}, and that the IDE leaves on the class path.
 */
final class IdeLaunch {

    /** What a launched JVM answered. */
    static final class Result {
        // A class, not a record: SonarQube reads this module at its main release, 9, and takes a record for an
        // empty method.
        private final int exit;
        private final String out;
        private final String err;

        Result(int exit, String out, String err) {
            this.exit = exit;
            this.out = out;
            this.err = err;
        }

        int exit() {
            return exit;
        }

        String out() {
            return out;
        }

        String err() {
            return err;
        }
    }

    private static final Map<String, Map<String, String>> MODULES = Map.of(
            "dep", Map.of(
                    "module-info.java", "module dep { exports dep; }",
                    "dep/Dep.java", "package dep; public final class Dep { }"),
            "lib", Map.of(
                    "module-info.java", "module lib { exports lib; requires dep; }",
                    "lib/Lib.java", "package lib; public class Lib { public void run() { } }"),
            "app", Map.of(
                    "app/internal/App.java", "package app.internal; public class App { }"),
            "api", Map.of(
                    "module-info.java", "module api { exports api; }",
                    "api/Api.java", "package api; public final class Api { public static void check() { } }"),
            "platform", Map.of(
                    "module-info.java", """
                            module platform {
                                exports spi;
                                exports internal to params;
                                opens hidden to params;
                                uses spi.Engine;
                            }
                            """,
                    "internal/Check.java", "package internal; public final class Check { public static void pass() { } }",
                    "hidden/Hidden.java", "package hidden; final class Hidden { private static void secret() { } }",
                    "spi/Engine.java", "package spi; public interface Engine { }",
                    "spi/Launcher.java", """
                            package spi;
                            public final class Launcher {
                                public static void run(String className) throws Exception {
                                    java.util.ServiceLoader.load(Engine.class).forEach(engine -> { });
                                    Class<?> type = Class.forName(className);
                                    var constructor = type.getDeclaredConstructor();
                                    constructor.setAccessible(true);
                                    Object test = constructor.newInstance();
                                    var run = type.getDeclaredMethod("run");
                                    run.setAccessible(true);
                                    run.invoke(test);
                                    System.out.println("ran " + className + " in module " + type.getModule().getName()
                                            + ", platform in module " + Launcher.class.getModule().getName());
                                }
                            }
                            """),
            "engine", Map.of(
                    "module-info.java",
                    "module engine { requires platform; requires api; provides spi.Engine with eng.Impl; }",
                    "eng/Impl.java", "package eng; public final class Impl implements spi.Engine { }"),
            "unused", Map.of(
                    "module-info.java", "module unused { exports unused; }",
                    "unused/Unused.java", "package unused; public final class Unused { }"));

    /** The friend of {@code platform}: it calls what is exported to it, and reflects on what is opened to it. */
    private static final Map<String, String> PARAMS = Map.of(
            "module-info.java", "module params { exports params; requires platform; }",
            "params/Params.java", """
                    package params;
                    public final class Params {
                        public static void check() { internal.Check.pass(); }
                        public static void reflect() throws Exception {
                            var secret = Class.forName("hidden.Hidden").getDeclaredMethod("secret");
                            secret.setAccessible(true);
                            secret.invoke(null);
                        }
                    }
                    """);

    private static final List<String> BUILD_ORDER =
            List.of("dep", "lib", "api", "platform", "engine", "unused", "app");

    /** The descriptor of the module under test, when a test does not say otherwise. */
    static final String APP = "module app { requires lib; }";

    private final Path modules;
    private final Path testClasses;
    private final Path starter;
    private final Path friendClasses;
    private final Path friendJar;
    private final Path logs;
    private int launches;

    IdeLaunch(Path root) throws IOException {
        this(root, APP);
    }

    /**
     * @param appDescriptor the {@code module-info.java} of the module under test
     */
    IdeLaunch(Path root, String appDescriptor) throws IOException {
        Path sources = root.resolve("src");
        modules = Files.createDirectories(root.resolve("modules"));
        testClasses = Files.createDirectories(root.resolve("test-classes"));
        starter = Files.createDirectories(root.resolve("starter"));
        friendClasses = Files.createDirectories(root.resolve("class-path").resolve("params"));
        friendJar = root.resolve("class-path").resolve("params.jar");
        logs = Files.createDirectories(root.resolve("logs"));

        for (String name : BUILD_ORDER) {
            List<Path> files = write(sources.resolve(name), MODULES.get(name));
            if (name.equals("app")) {
                files.addAll(write(sources.resolve(name), Map.of("module-info.java", appDescriptor)));
            }
            // platform names a friend its own build does not hold: javac would warn about it on every fixture.
            compile(files, "-Xlint:-module", "-d", modules.resolve(name).toString(),
                    "--module-path", modules.toString());
        }
        compile(write(sources.resolve("params"), PARAMS),
                "-d", friendClasses.toString(), "--module-path", modules.toString());
        jar(friendJar, friendClasses);
        Path testSources = sources.resolve("app-test");
        compile(write(testSources, Map.of("app/internal/AppTest.java", """
                        package app.internal;
                        class AppTest {
                            AppTest() { }
                            void run() { api.Api.check(); }
                        }
                        """, "app/internal/AppFriendTest.java", """
                        package app.internal;
                        class AppFriendTest {
                            AppFriendTest() { }
                            void run() { params.Params.check(); }
                        }
                        """, "app/internal/AppDeepFriendTest.java", """
                        package app.internal;
                        class AppDeepFriendTest {
                            AppDeepFriendTest() { }
                            void run() throws Exception { params.Params.reflect(); }
                        }
                        """)),
                "-d", testClasses.toString(), "--module-path", modules.toString(),
                "--class-path", friendClasses.toString(),
                "--patch-module", "app=" + testSources, "--add-modules", "api",
                "--add-reads", "app=api,ALL-UNNAMED");
        compile(write(sources.resolve("starter"), Map.of("starter/Main.java", """
                        package starter;
                        public final class Main {
                            public static void main(String[] args) throws Exception { spi.Launcher.run(args[0]); }
                        }
                        """)),
                "-d", starter.toString(), "--module-path", modules.toString(), "--add-modules", "platform");
    }

    /** The launch IntelliJ builds: the test libraries are named modules, opened and read towards the class path. */
    List<String> ideCommand(String... jvmOptions) {
        return ideCommand(Stream.of(starter), "app.internal.AppTest", jvmOptions);
    }

    /**
     * The launch IntelliJ builds for a test that calls {@code params}, a test library it left on the class path.
     *
     * @param friend where {@code params} is: {@link #friendJar()} or {@link #friendClasses()}
     * @param testClass the test to run
     */
    List<String> friendCommand(Path friend, String testClass, String... jvmOptions) {
        return ideCommand(Stream.of(starter, friend), testClass, jvmOptions);
    }

    /** {@code params} as a jar that holds its descriptor. */
    Path friendJar() {
        return friendJar;
    }

    /** {@code params} as a folder of classes that holds its descriptor. */
    Path friendClasses() {
        return friendClasses;
    }

    private List<String> ideCommand(Stream<Path> classPath, String testClass, String[] jvmOptions) {
        return command(jvmOptions,
                "--patch-module", "app=" + testClasses,
                "--add-reads", "app=ALL-UNNAMED",
                "--add-opens", "app/app.internal=ALL-UNNAMED",
                "--add-opens", "app/app.internal=lib",
                "--add-modules", "app",
                "--add-modules", "platform",
                "-p", path(modules("platform", "lib", "dep", "app", "api", "engine", "unused")),
                "-cp", path(classPath),
                "starter.Main", testClass);
    }

    /** The launch Maven builds: the test libraries are on the class path. */
    List<String> classPathCommand(String... jvmOptions) {
        return command(jvmOptions,
                "--patch-module", "app=" + testClasses,
                "--add-reads", "app=ALL-UNNAMED",
                "--add-opens", "app/app.internal=ALL-UNNAMED",
                "--add-modules", "app",
                "-p", path(modules("lib", "dep", "app")),
                "-cp", path(Stream.concat(Stream.of(starter), modules("platform", "api", "engine"))),
                "starter.Main", "app.internal.AppTest");
    }

    /** A launch that patches nothing and runs a public class of an exported package. */
    List<String> plainCommand(String... jvmOptions) {
        return command(jvmOptions,
                "--add-modules", "app,platform",
                "-p", path(modules("platform", "lib", "dep", "app", "api", "engine", "unused")),
                "-cp", starter.toString(),
                "starter.Main", "lib.Lib");
    }

    Result run(List<String> command) throws IOException, InterruptedException {
        Path out = logs.resolve(++launches + ".out");
        Path err = logs.resolve(launches + ".err");
        ProcessBuilder builder = new ProcessBuilder(command).redirectOutput(out.toFile()).redirectError(err.toFile());
        // Options a machine gives every JVM would be announced on the standard error stream the tests read.
        builder.environment().remove("JAVA_TOOL_OPTIONS");
        builder.environment().remove("JDK_JAVA_OPTIONS");
        Process process = builder.start();
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("The launched JVM did not end: " + command);
        }
        return new Result(process.exitValue(), Files.readString(out), Files.readString(err));
    }

    private Stream<Path> modules(String... names) {
        return Stream.of(names).map(modules::resolve);
    }

    private static String path(Stream<Path> entries) {
        return entries.map(Path::toString).collect(Collectors.joining(File.pathSeparator));
    }

    private static List<String> command(String[] jvmOptions, String... rest) {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.addAll(coverageAgent());
        command.addAll(List.of(jvmOptions));
        command.addAll(List.of(rest));
        return command;
    }

    /**
     * The coverage agent of this test JVM, when the build runs one: the agent under test runs in the launched JVM
     * only, and that run is the module's coverage. Surefire may put the options in an {@code @} argument file. The
     * coverage agent instruments this package only, so the launched modules stay as they are compiled.
     */
    static List<String> coverageAgent() {
        String[] arguments = ProcessHandle.current().info().arguments().orElse(new String[0]);
        return Stream.of(arguments).flatMap(IdeLaunch::expanded)
                .filter(argument -> argument.startsWith("-javaagent:") && argument.contains("jacoco"))
                .map(argument -> argument + (argument.contains("=") ? "," : "=")
                        + "includes=" + PatchedModuleAgent.class.getPackageName() + ".*")
                .toList();
    }

    private static Stream<String> expanded(String argument) {
        if (!argument.startsWith("@")) {
            return Stream.of(argument);
        }
        try {
            return Files.readAllLines(Path.of(argument.substring(1))).stream()
                    .flatMap(line -> Stream.of(line.trim().split("\\s+")))
                    .map(IdeLaunch::unquoted)
                    .filter(token -> !token.isEmpty());
        } catch (IOException _) {
            return Stream.empty();
        }
    }

    private static String unquoted(String token) {
        return token.length() > 1 && token.startsWith("\"") && token.endsWith("\"")
                ? token.substring(1, token.length() - 1) : token;
    }

    private static List<Path> write(Path directory, Map<String, String> sources) throws IOException {
        List<Path> files = new ArrayList<>();
        for (Map.Entry<String, String> source : sources.entrySet()) {
            Path file = directory.resolve(source.getKey());
            Files.createDirectories(file.getParent());
            files.add(Files.writeString(file, source.getValue()));
        }
        return files;
    }

    private static void jar(Path jar, Path classes) {
        ToolProvider tool = ToolProvider.findFirst("jar").orElseThrow();
        if (tool.run(System.out, System.err, "--create", "--file", jar.toString(), "-C", classes.toString(), ".") != 0) {
            throw new IllegalStateException("The fixture does not package: " + jar);
        }
    }

    private static void compile(List<Path> files, String... options) {
        // javac through java.util.spi: the module of these tests reads java.base, not java.compiler.
        ToolProvider compiler = ToolProvider.findFirst("javac").orElseThrow();
        List<String> arguments = new ArrayList<>(List.of(options));
        files.forEach(file -> arguments.add(file.toString()));
        if (compiler.run(System.out, System.err, arguments.toArray(String[]::new)) != 0) {
            throw new IllegalStateException("The fixture does not compile: " + arguments);
        }
    }
}
