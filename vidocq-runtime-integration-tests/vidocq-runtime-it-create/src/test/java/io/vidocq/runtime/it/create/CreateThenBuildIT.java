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
package io.vidocq.runtime.it.create;

import io.vidocq.runtime.tooling.ProjectScaffolder;
import io.vidocq.runtime.tooling.ProjectSpec;
import io.vidocq.runtime.tooling.Reporter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.regex.Pattern;
import java.util.spi.ToolProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Vidocq/vidocq#339 (evolution 6) and #380: the project {@code vidocq create -x cassini-rest} writes passes
 * {@code mvn verify vidocq:jlink} on this build. Without a parent, the plugin declared as a build extension gives the
 * build what {@code vidocq-runtime-parent} carried, and wires the codegen bundles: the Cassini processor writes the
 * resource's adapter source into {@code target/generated-sources/annotations}, the first test starts the application
 * with {@code @VidocqTest} and calls {@code GET /hello}, {@code vidocq:package} writes the distribution, and
 * {@code vidocq:jlink} the self-contained image ({@code vidocq build jlink}). With {@code --with-parent}, the same
 * project builds under the parent.
 */
class CreateThenBuildIT {

    private static final Duration TIMEOUT = Duration.ofMinutes(5);

    /** The fixture module that holds only {@code META-INF/resources/x.css} and that the application does not require. */
    private static final String RESOURCE_MODULE = "acme.resources";

    /**
     * The javac warnings the created project is allowed: one per class Vauban writes in javac's last round (#337,
     * documented). From Vidocq/vauban#251 on, Vauban writes them earlier and there are none (BUG-20261030-26); until
     * that Vauban is the one this build uses, two are still allowed.
     */
    private static final String LAST_ROUND = "created in the last round will not be subject to annotation processing";

    /** How long a packaged application may take to print the last line of its boot. */
    private static final Duration START_TIMEOUT = Duration.ofMinutes(1);

    /** What {@code vidocq:generate} writes of the build compatible extensions it ran (Vidocq/vauban#288). */
    private static final String FROZEN_RECORD = "META-INF/vauban-frozen-extensions.properties";

    /** What {@code vidocq:generate} logs once it has frozen the build compatible extensions. */
    private static final Pattern FROZE = Pattern.compile("Vauban: froze [1-9]\\d* build compatible extension\\(s\\)");

    /** The {@code cdi extensions} row of the startup report of a start that loaded the record of the build. */
    private static final Pattern FROZEN_AT_BUILD =
            Pattern.compile("cdi extensions\\s+[1-9]\\d* extensions? frozen at build");

    /** The last line of a boot. */
    private static final String STARTED = "Vidocq - Started in";

    /** The IT checks what the scaffolder writes and what the build prints, not what the scaffolder says. */
    private static final Reporter SILENT = new Reporter() {
        @Override public void success(String message, String detail) { /* see SILENT */ }
        @Override public void note(String message) { /* see SILENT */ }
        @Override public void warning(String message) { /* see SILENT */ }
        @Override public void error(String message) { /* see SILENT */ }
    };

    @Test
    void aCreatedRestApplicationBuildsTestsItselfPackagesAndLinksWithoutAWarning(@TempDir Path dir) throws Exception {
        String version = System.getProperty("it.version");
        ProjectScaffolder.scaffold(new ProjectSpec("demo-app", "com.acme", "com.acme.demo.app",
                Set.of("cassini-rest"), null, false), dir, version, SILENT);
        Path project = dir.resolve("demo-app");
        useThisBuildsArtifacts(project);
        addAResourceOnlyDependency(project);

        Path log = Files.createDirectories(Path.of(System.getProperty("it.logs"))).resolve("create-then-build.log");
        Process build = new ProcessBuilder("mvn", "-ntp", "-B", "verify", "vidocq:jlink")
                .directory(project.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        if (!build.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
            build.destroyForcibly();
            fail("mvn verify vidocq:jlink did not end within " + TIMEOUT + ":\n" + read(log));
        }
        String output = read(log);
        assertEquals(0, build.exitValue(), () -> "mvn verify vidocq:jlink failed:\n" + output);
        String pom = Files.readString(project.resolve("pom.xml"));
        assertFalse(pom.contains("<parent>"), "the created project builds without a parent (#380):\n" + pom);
        assertTrue(pom.contains("<extensions>true</extensions>"), pom);
        assertFalse(pom.contains("annotationProcessorPaths"),
                "the plugin wires the codegen bundles: the created pom lists none");
        assertTrue(output.contains("no Vidocq parent in the pom, the plugin adds"),
                "the plugin did not complete the build of the parentless project:\n" + output);
        // What the plugin wired runs: the Cassini processor writes the resource's adapter, as a source the IDE sees.
        assertTrue(Files.isRegularFile(project.resolve(
                        "target/generated-sources/annotations/com/acme/demo/app/HelloResource$$CassiniAdapter.java")),
                "the Cassini processor wrote no adapter source:\n" + output);
        assertTrue(Files.isRegularFile(project.resolve("target/classes/META-INF/vidocq/build-info/demo-app.properties")),
                "vidocq:build-info wrote no build identity:\n" + output);

        assertTrue(output.contains("Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed")
                && output.contains("in com.acme.demo.app.HelloResourceTest"), "the first test did not run:\n" + output);
        assertTrue(Files.isRegularFile(project.resolve("target/demo-app-1.0.0-SNAPSHOT/bin/demo-app.sh")),
                "vidocq:package wrote no launcher:\n" + output);
        assertTrue(Files.isRegularFile(project.resolve("target/dist/bin/demo-app")),
                "vidocq:jlink wrote no image launcher:\n" + output);
        assertTrue(modules(project.resolve("target/dist/bin/java")).contains("com.acme.demo.app"),
                "the image does not link the application module:\n" + output);
        List<String> warnings = output.lines()
                .filter(l -> l.startsWith("[WARNING]") && !l.contains(LAST_ROUND))
                .toList();
        assertEquals(List.of(), warnings, "the created project builds with warnings:\n" + output);
        long lastRound = output.lines().filter(l -> l.contains(LAST_ROUND)).count();
        assertTrue(lastRound == 0 || lastRound == 2, () -> lastRound + " last-round warnings:\n" + output);

        assertTheBuildFrozeItsExtensions(project, output);
        assertStartsOnTheFrozenExtensions(project.resolve("target/demo-app-1.0.0-SNAPSHOT/bin/demo-app.sh"));
        // The image is linked with --strip-debug, and its application module sits in the boot layer as well as in
        // the layer Vauban defines: it starts on the same record all the same (Vidocq/vauban BUG-20261113-17).
        assertStartsOnTheFrozenExtensions(project.resolve("target/dist/bin/demo-app"));
        assertTheResourceOnlyModuleIsResolvedAtStart(project.resolve("target/dist/bin/java"), output);
    }

    /**
     * A module that nothing {@code requires} (Vaadin's resource-only {@code flow.push}) is linked into the image, and
     * is served only if the image resolves it at start: the options baked into the image root every staged module
     * (BUG-20261114-05). The resolution the JVM prints for the image's own launch names the fixture module.
     */
    private static void assertTheResourceOnlyModuleIsResolvedAtStart(Path java, String output) throws Exception {
        assertTrue(modules(java).contains(RESOURCE_MODULE), "the image does not link " + RESOURCE_MODULE + ":\n" + output);
        Process dry = new ProcessBuilder(java.toString(), "--show-module-resolution", "--dry-run",
                "-m", "io.vidocq.runtime.core/io.vidocq.runtime.core.Vidocq")
                .redirectErrorStream(true).start();
        String resolution = new String(dry.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(dry.waitFor(1, TimeUnit.MINUTES), "the dry run did not end");
        assertTrue(resolution.lines().anyMatch(l -> (" " + l + " ").contains(" " + RESOURCE_MODULE + " ")),
                "linked in the image, resolved by no start:\n" + resolution);
    }

    /**
     * Installs, in the repository the created project builds from, a module that holds only
     * {@code META-INF/resources/x.css} and that nothing requires, and adds it to the project's dependencies.
     */
    private static void addAResourceOnlyDependency(Path project) throws Exception {
        Path directory = Files.createDirectories(Path.of(System.getProperty("it.repo"))
                .resolve("com/acme/resource-only/1.0"));
        Files.writeString(directory.resolve("resource-only-1.0.pom"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.acme</groupId>
                    <artifactId>resource-only</artifactId>
                    <version>1.0</version>
                </project>
                """);
        Path sources = Files.createDirectories(project.resolveSibling("resource-only-src"));
        Path classes = Files.createDirectories(project.resolveSibling("resource-only-classes"));
        Files.writeString(sources.resolve("module-info.java"), "module " + RESOURCE_MODULE + " {}\n");
        var javac = ToolProvider.findFirst("javac").orElseThrow();
        assertEquals(0, javac.run(System.out, System.err, "-d", classes.toString(),
                sources.resolve("module-info.java").toString()), "javac failed on the fixture module");
        try (var jar = new JarOutputStream(Files.newOutputStream(directory.resolve("resource-only-1.0.jar")))) {
            jar.putNextEntry(new JarEntry("module-info.class"));
            jar.write(Files.readAllBytes(classes.resolve("module-info.class")));
            jar.closeEntry();
            jar.putNextEntry(new JarEntry("META-INF/resources/x.css"));
            jar.write("body {}\n".getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        Path pom = project.resolve("pom.xml");
        String text = Files.readString(pom);
        // The project's own dependencies follow the dependencyManagement, which the created pom may start with.
        int end = text.indexOf("</dependencies>", Math.max(0, text.indexOf("</dependencyManagement>")));
        assertTrue(end > 0, "the created pom has no dependencies:\n" + text);
        Files.writeString(pom, text.substring(0, end)
                + "<dependency><groupId>com.acme</groupId><artifactId>resource-only</artifactId><version>1.0</version></dependency>\n"
                + text.substring(end));
    }

    /** {@code vidocq create --with-parent}: the previous style still builds and tests itself under the parent. */
    @Test
    void aCreatedApplicationWithTheParentStillBuildsAndTestsItself(@TempDir Path dir) throws Exception {
        String version = System.getProperty("it.version");
        ProjectScaffolder.scaffold(new ProjectSpec("demo-app", "com.acme", "com.acme.demo.app",
                Set.of("cassini-rest"), null, false, false, true), dir, version, SILENT);
        Path project = dir.resolve("demo-app");
        useThisBuildsArtifacts(project);

        Path log = Files.createDirectories(Path.of(System.getProperty("it.logs"))).resolve("create-with-parent.log");
        Process build = new ProcessBuilder("mvn", "-ntp", "-B", "verify")
                .directory(project.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        if (!build.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
            build.destroyForcibly();
            fail("mvn verify did not end within " + TIMEOUT + ":\n" + read(log));
        }
        String output = read(log);
        assertEquals(0, build.exitValue(), () -> "mvn verify failed:\n" + output);
        String pom = Files.readString(project.resolve("pom.xml"));
        assertTrue(pom.contains("<artifactId>vidocq-runtime-parent</artifactId>"), pom);
        assertFalse(output.contains("no Vidocq parent in the pom"), "the plugin added to a project under the parent:\n"
                + output);
        assertTrue(output.contains("in com.acme.demo.app.HelloResourceTest"), "the first test did not run:\n" + output);
        assertTrue(Files.isRegularFile(project.resolve("target/demo-app-1.0.0-SNAPSHOT/bin/demo-app.sh")),
                "vidocq:package wrote no launcher:\n" + output);

        assertTheBuildFrozeItsExtensions(project, output);
        assertStartsOnTheFrozenExtensions(project.resolve("target/demo-app-1.0.0-SNAPSHOT/bin/demo-app.sh"));
    }

    /**
     * Vidocq/vauban#288, Vidocq/vidocq#548: {@code vidocq:generate}, which the created project runs at
     * {@code process-classes}, ran the build compatible extensions once, said so, and wrote what they did.
     */
    private static void assertTheBuildFrozeItsExtensions(Path project, String output) {
        assertTrue(Files.isRegularFile(project.resolve("target/classes/" + FROZEN_RECORD)),
                "vidocq:generate wrote no record of the build compatible extensions:\n" + output);
        assertTrue(FROZE.matcher(output).find(),
                "vidocq:generate did not freeze the build compatible extensions:\n" + output);
    }

    /**
     * Starts what the build packaged and reads its detailed startup report: the start loaded what the build recorded
     * instead of running the build compatible extensions. The record is used only for the deployment it was written
     * for, so this fails when the packaged path is not the one {@code vidocq:generate} froze.
     *
     * <p>The application listens on a port the system picks (port 0), never on 8080, which another process of the
     * machine may hold, and only the process started here is stopped.
     */
    private static void assertStartsOnTheFrozenExtensions(Path launcher) throws Exception {
        ProcessBuilder command = new ProcessBuilder(launcher.toString()).redirectErrorStream(true);
        command.environment().put("JAVA_HOME", System.getProperty("java.home"));
        // The java launcher reads it itself, whatever the launcher: the one of a jlink image passes no JAVA_OPTS. A
        // start that does not load the record of its build fails, and its message names the classes that differ.
        command.environment().put("JDK_JAVA_OPTIONS", "-Dvidocq.http.port=0 -Dvidocq.startup.report=detailed"
                + " -Dvidocq.startup.require-frozen-extensions=true");
        Process application = command.start();
        String boot;
        try (ExecutorService reader = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> started = reader.submit(() -> untilStarted(application));
            try {
                boot = started.get(START_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                throw new AssertionError(launcher + " did not start within " + START_TIMEOUT, e);
            } finally {
                // Ends the read as well: the stream of a stopped process closes.
                stop(launcher, application);
            }
        }
        assertTrue(boot.contains(STARTED), launcher + " did not start:\n" + boot);
        assertFalse(boot.contains(":8080/"), launcher + " listened on the default port:\n" + boot);
        assertTrue(FROZEN_AT_BUILD.matcher(boot).find(),
                launcher + " did not start on the extensions frozen at build:\n" + boot);
    }

    /**
     * Stops the application, the processes its launcher started first. The launcher of a jlink image runs java
     * without exec: its JVM is a child of the script, and stopping the script alone leaves the JVM running after the
     * test (BUG-20261114-02). The JVM is waited for while the script still runs, so that the script reaps it: once
     * orphaned, it would stay a zombie in a container whose first process reaps nothing ({@code tail -f /dev/null}
     * in the CI), and the check would fail on it.
     */
    private static void stop(Path launcher, Process application) throws InterruptedException {
        List<ProcessHandle> children = application.descendants().toList();
        try {
            children.forEach(ProcessHandle::destroy);
            assertStopped(launcher, children);
        } finally {
            application.destroy();
            if (!application.waitFor(30, TimeUnit.SECONDS)) {
                application.destroyForcibly();
            }
        }
    }

    /** Every process the launcher started has ended: none outlives the test, holding its port (BUG-20261114-02). */
    private static void assertStopped(Path launcher, List<ProcessHandle> started) throws InterruptedException {
        for (ProcessHandle process : started) {
            try {
                process.onExit().get(30, TimeUnit.SECONDS);
            } catch (ExecutionException | TimeoutException e) {
                String command = process.info().commandLine().orElse("pid " + process.pid());
                process.destroyForcibly();
                fail(launcher + " left a process running after it was stopped: " + command, e);
            }
        }
    }

    /** What the application prints up to the last line of its boot, or up to its end when it does not start. */
    private static String untilStarted(Process application) throws IOException {
        StringBuilder boot = new StringBuilder();
        try (BufferedReader lines = application.inputReader(StandardCharsets.UTF_8)) {
            String line;
            while ((line = lines.readLine()) != null) {
                boot.append(line).append('\n');
                if (line.contains(STARTED)) {
                    break;
                }
            }
        }
        return boot.toString();
    }

    /**
     * The created project's builds resolve this build's artifacts from target/it-repo and everything else from the
     * outer local repository, through {@code .mvn/maven.config}: they never reach the network.
     */
    private static void useThisBuildsArtifacts(Path project) throws IOException {
        Path settings = Path.of(System.getProperty("it.settings"));
        String url = Path.of(System.getProperty("it.outerRepository")).toUri().toString();
        String template = Files.readString(Path.of(System.getProperty("it.settings.template")));
        Files.writeString(settings, template.replace("@localRepositoryUrl@", url));
        Files.writeString(project.resolve(".mvn/maven.config"),
                "-Dmaven.repo.local=" + System.getProperty("it.repo") + "\n--settings\n" + settings + "\n");
    }

    /** What {@code java --list-modules} of an image prints. */
    private static String modules(Path java) throws IOException, InterruptedException {
        Process list = new ProcessBuilder(java.toString(), "--list-modules").redirectErrorStream(true).start();
        String out = new String(list.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        list.waitFor(1, TimeUnit.MINUTES);
        return out;
    }

    private static String read(Path file) {
        try {
            return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "(no file)";
        } catch (IOException e) {
            return "(unreadable: " + e.getMessage() + ")";
        }
    }
}
