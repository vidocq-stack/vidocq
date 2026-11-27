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
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.spi.ToolProvider;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Vidocq/vidocq#339 (evolution 6) and #380: the project {@code vidocq create -x rest-cassini} writes passes
 * {@code mvn verify vidocq:jlink} on this build. Without a parent, the plugin declared as a build extension gives the
 * build what {@code vidocq-runtime-parent} carried, and wires the codegen bundles: the Cassini processor writes the
 * resource's adapter source into {@code target/generated-sources/annotations}, the first test starts the application
 * with {@code @VidocqTest} and calls {@code GET /hello}, {@code vidocq:package} writes the distribution, and
 * {@code vidocq:jlink} the self-contained image ({@code vidocq build jlink}). With {@code --with-parent}, the same
 * project builds under the parent. A created servlet application, packaged, injects Foy's request, session and
 * context beans (Vidocq/foy#107), and starts without running a build compatible extension: they are frozen at build,
 * or they all ran at compile.
 */
class CreateThenBuildIT {

    private static final Duration TIMEOUT = Duration.ofMinutes(5);

    /** The fixture module that holds only {@code META-INF/resources/x.css} and that the application does not require. */
    private static final String RESOURCE_MODULE = "acme.resources";

    /** How long a packaged application may take to print the last line of its boot. */
    private static final Duration START_TIMEOUT = Duration.ofMinutes(1);

    /** What {@code vidocq:generate} writes of the build compatible extensions it ran (Vidocq/vauban#288). */
    private static final String FROZEN_RECORD = "META-INF/vauban-frozen-extensions.properties";

    /** What {@code vidocq:generate} logs once it has frozen the build compatible extensions. */
    private static final Pattern FROZE = Pattern.compile("Vauban: froze [1-9]\\d* build compatible extension\\(s\\)");

    /** The {@code cdi extensions} row of the startup report of a start that loaded the record of the build. */
    private static final Pattern FROZEN_AT_BUILD =
            Pattern.compile("cdi extensions\\s+[1-9]\\d* extensions? frozen at build");

    /**
     * What {@code vidocq:generate} logs when every bean archive of the deployment was processed at compile: the
     * extensions ran then, and the start runs none of them (Vidocq BUG-20261115-05, Vauban BUG-20261115-07).
     */
    private static final String RAN_AT_COMPILE =
            "[INFO] Vauban: nothing to freeze: every build compatible extension of the deployment ran at compile.";

    /**
     * The {@code cdi extensions} row of the startup report, whatever it says: a start that neither ran an extension
     * nor loaded a record has none.
     */
    private static final Pattern CDI_EXTENSIONS_ROW = Pattern.compile("^\\s+cdi extensions\\s", Pattern.MULTILINE);

    /**
     * The {@code weaving} row of the {@code layer} section of the detailed startup report, which the {@code cdi
     * extensions} row follows when there is one: the report was printed, so a missing row is really missing. Not the
     * {@code weaving 4 ms} of the {@code phases} row.
     */
    private static final Pattern WEAVING_ROW = Pattern.compile("^\\s+weaving\\s+(?!\\d)\\S", Pattern.MULTILINE);

    /**
     * The prefix of the anomalies of a start that misses what the build step did: no {@code vidocq:generate} index
     * ({@code VIDOCQ-GEN-001}), no processor output ({@code -002}), no usable record of the extensions ({@code -003}).
     */
    private static final String GENERATE_ANOMALY = "[VIDOCQ-GEN-";

    /** The last line of a boot. */
    private static final String STARTED = "Vidocq - Started in";

    /** The detailed startup report, which has the {@code cdi extensions} row. */
    private static final String REPORT = "-Dvidocq.startup.report=detailed";

    /**
     * The options of a start that must run on the record of its build: a start that runs an extension without it
     * fails, and its message names the classes that differ.
     */
    private static final String ON_THE_RECORD = REPORT + " -Dvidocq.startup.require-frozen-extensions=true";

    /** The context path of the created servlet application, off the root where its REST resources are. */
    private static final String CONTEXT_PATH = "/demo";

    /** The line Chappe logs once the default listener listens, with the port it bound. */
    private static final Pattern LISTENING = Pattern.compile("Chappe listener 'default' started on http://[^/]+:(\\d+)/");

    /** What Vauban prints when the container generates a class at run time instead of at build. */
    private static final String RUNTIME_GENERATION = "RUNTIME CODE GENERATION FALLBACK";

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
                Set.of("rest-cassini"), null, false), dir, version, SILENT);
        Path project = dir.resolve("demo-app");
        useThisBuildsArtifacts(project);
        addAResourceOnlyDependency(project);

        String output = build(project, "create-then-build.log", "verify", "vidocq:jlink");
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
        List<String> warnings = BuildWarnings.warningsOf(output, outerRepositoryUrl());
        assertEquals(List.of(), warnings, "the created project builds with warnings:\n" + output);

        assertTheBuildFrozeItsExtensions(project, output);
        assertStartsOnTheFrozenExtensions(project.resolve("target/demo-app-1.0.0-SNAPSHOT/bin/demo-app.sh"));
        // The image is linked with --strip-debug: it starts on the same record all the same (Vidocq/vauban
        // BUG-20261113-17).
        String imageBoot = assertStartsOnTheFrozenExtensions(project.resolve("target/dist/bin/demo-app"));
        assertTheImageLayersTheApplicationOnce(project.resolve("target/dist/bin/java"), imageBoot, output);
    }

    /**
     * The image has one layer, the JVM's own (BUG-20261114-11, Vidocq/vidocq#580): the start builds no application
     * layer. A module that nothing {@code requires} (Vaadin's resource-only {@code flow.push}) is served only if it
     * is resolved: the image roots every module the build staged (BUG-20261114-05), so the resolution the JVM prints
     * for the image's own launch names the fixture module and the application module.
     */
    private static void assertTheImageLayersTheApplicationOnce(Path java, String boot, String output) throws Exception {
        assertTrue(modules(java).contains(RESOURCE_MODULE), "the image does not link " + RESOURCE_MODULE + ":\n" + output);
        assertTrue(boot.contains("no application layer"), "the image has one layer:\n" + boot);
        assertTrue(boot.lines().noneMatch(l -> l.contains("Application layer ready")),
                "the image built an application layer:\n" + boot);
        Process dry = new ProcessBuilder(java.toString(), "--show-module-resolution", "--dry-run",
                "-m", "io.vidocq.runtime.core/io.vidocq.runtime.core.Vidocq")
                .redirectErrorStream(true).start();
        String resolution = new String(dry.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(dry.waitFor(1, TimeUnit.MINUTES), "the dry run did not end");
        for (String module : List.of(RESOURCE_MODULE, "com.acme.demo.app")) {
            assertTrue(resolution.lines().anyMatch(l -> (" " + l + " ").contains(" " + module + " ")),
                    module + " is not resolved by the image:\n" + resolution);
        }
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
                Set.of("rest-cassini"), null, false, false, true), dir, version, SILENT);
        Path project = dir.resolve("demo-app");
        useThisBuildsArtifacts(project);

        String output = build(project, "create-with-parent.log", "verify");
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
     * BUG-20261117-06: the build identity names the commit the application is built from in a linked worktree
     * ({@code git worktree add}), and that commit's time, with the parent as without it. Under the parent,
     * git-commit-id-maven-plugin named the main checkout's commit there.
     */
    @ParameterizedTest(name = "with the parent: {0}")
    @ValueSource(booleans = {true, false})
    void anApplicationBuiltFromALinkedWorktreeNamesTheWorktreesCommit(boolean withParent, @TempDir Path dir)
            throws Exception {
        assumeTrue(gitAnswers(), "no git command on this machine");
        Path main = committedApplication(dir.resolve("main"), withParent);
        Path worktree = dir.resolve("worktree");
        git(main, "worktree", "add", "-q", "-b", "ahead", worktree.toString());
        Files.writeString(worktree.resolve("AHEAD.txt"), "one commit ahead of the main checkout\n");
        git(worktree, "add", "AHEAD.txt");
        git(worktree, "commit", "-q", "-m", "one commit ahead");

        String output = build(worktree.resolve("demo-app"), Map.of(),
                "linked-worktree-" + (withParent ? "parent" : "parentless") + ".log", "process-resources");

        Properties identity = identity(worktree.resolve("demo-app"));
        String mainCommit = git(main, "rev-parse", "--short=8", "HEAD");
        assertEquals(git(worktree, "rev-parse", "--short=8", "HEAD"), identity.getProperty("git.commit.id.abbrev"),
                () -> "the build identity names another commit than the worktree's (the main checkout's is "
                        + mainCommit + "):\n" + output);
        assertEquals(Instant.ofEpochSecond(Long.parseLong(git(worktree, "show", "-s", "--format=%ct", "HEAD")))
                .toString(), identity.getProperty("git.commit.time"), () -> "the worktree commit's time:\n" + output);
    }

    /**
     * BUG-20261117-06: on a machine without a {@code git} command the application still builds, names its commit, and
     * says what the build identity lacks: the dirty flag and the commit time, which only the command can tell.
     */
    @ParameterizedTest(name = "with the parent: {0}")
    @ValueSource(booleans = {true, false})
    @DisabledOnOs(OS.WINDOWS)
    void anApplicationBuiltWithoutAGitCommandNamesItsCommitAndSaysWhatItLacks(boolean withParent, @TempDir Path dir)
            throws Exception {
        assumeTrue(gitAnswers(), "no git command on this machine to make the repository");
        Path repository = committedApplication(dir.resolve("repository"), withParent);
        Path noGit = dir.resolve("no-git");
        String output;
        try {
            output = build(repository.resolve("demo-app"), Map.of("PATH", pathWithoutGit(noGit)),
                    "no-git-" + (withParent ? "parent" : "parentless") + ".log", "process-resources");
        } finally {
            removeLinks(noGit);
        }

        Properties identity = identity(repository.resolve("demo-app"));
        assertEquals(git(repository, "rev-parse", "--short=8", "HEAD"), identity.getProperty("git.commit.id.abbrev"),
                output);
        assertNull(identity.getProperty("git.dirty"), "only the git command tells it:\n" + output);
        assertNull(identity.getProperty("git.commit.time"), "only the git command tells it:\n" + output);
        assertTrue(output.contains("without the dirty flag and the commit time: the git command did not answer"),
                output);
    }

    /**
     * BUG-20261117-06: an application built out of any repository (a source archive) builds, and its build identity
     * says {@code unversionned}, with the parent as without it.
     */
    @ParameterizedTest(name = "with the parent: {0}")
    @ValueSource(booleans = {true, false})
    void anApplicationBuiltFromASourceArchiveIsUnversionned(boolean withParent, @TempDir Path dir) throws Exception {
        for (Path parent = dir; parent != null; parent = parent.getParent()) {
            assumeFalse(Files.exists(parent.resolve(".git")), "the temporary directory is inside a git repository");
        }
        Path archive = application(dir.resolve("archive"), withParent);

        String output = build(archive.resolve("demo-app"), Map.of(),
                "source-archive-" + (withParent ? "parent" : "parentless") + ".log", "process-resources");

        assertEquals("unversionned", identity(archive.resolve("demo-app")).getProperty("git.commit.id.abbrev"), output);
        assertTrue(output.contains("unversionned (no git repository)"), output);
    }

    /**
     * The created servlet applications. With REST's extensions, whose archives make {@code vidocq:generate} freeze
     * every extension, Foy's included, the start must load that record. Foy's extensions alone all run when the
     * application compiles: the build has nothing to freeze and says so, and the start, required to run on a record
     * all the same, runs none of them (BUG-20261115-05, fixed in Vauban: BUG-20261115-07).
     */
    static Stream<Arguments> servletApplications() {
        return Stream.of(
                Arguments.of("servlet-rest", Set.of("servlet-foy", "rest-cassini"), true),
                Arguments.of("servlet", Set.of("servlet-foy"), false));
    }

    /**
     * Vidocq/foy#107 on the packaged path: in the project {@code vidocq create} writes, a servlet injects the
     * {@code HttpServletRequest}, the {@code HttpSession} and the {@code ServletContext}, and an application
     * {@code ServletContextListener} uses the injected context in {@code contextInitialized}. The packaged
     * application starts under {@code vidocq.startup.require-frozen-extensions=true}: on the record
     * {@code vidocq:generate} wrote when it froze the extensions, or, when they all ran at compile, without running
     * any. The three beans answer over HTTP, and the container generates no class at run time: their client proxies
     * come from Foy's build.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("servletApplications")
    void aPackagedServletApplicationInjectsTheRequestTheSessionAndTheContext(
            String name, Set<String> extensions, boolean frozen, @TempDir Path dir) throws Exception {
        String version = System.getProperty("it.version");
        ProjectScaffolder.scaffold(new ProjectSpec("demo-app", "com.acme", "com.acme.demo.app",
                extensions, null, false), dir, version, SILENT);
        Path project = dir.resolve("demo-app");
        useThisBuildsArtifacts(project);
        addTheWhoAmIServlet(project);

        String output = build(project, "create-" + name + ".log", "verify");
        List<String> warnings = BuildWarnings.warningsOf(output, outerRepositoryUrl());
        assertEquals(List.of(), warnings, "the created " + name + " project builds with warnings:\n" + output);
        if (frozen) {
            assertTheBuildFrozeItsExtensions(project, output);
            String frozenRecord = Files.readString(project.resolve("target/classes/" + FROZEN_RECORD));
            for (String extension : List.of("io.vidocq.foy.cdi.vauban.FoyWebExtension",
                    "io.vidocq.vauban.webcontexts.WebContextsExtension")) {
                assertTrue(frozenRecord.contains(extension),
                        extension + " is not in the record of the build:\n" + frozenRecord);
            }
        } else {
            assertTheBuildHadNothingToFreeze(project, output);
        }

        Path launcher = project.resolve("target/demo-app-1.0.0-SNAPSHOT/bin/demo-app.sh");
        Path log = logs().resolve("create-" + name + "-run.log");
        String options = "-Dvidocq.http.host=127.0.0.1 -Dvidocq.http.port=0 " + ON_THE_RECORD;
        Process application = launch(launcher, options).redirectOutput(log.toFile()).start();
        try {
            // Started under require-frozen-extensions: no extension ran at start without the record of the build.
            String boot = awaitStarted(application, log);
            if (frozen) {
                assertTrue(FROZEN_AT_BUILD.matcher(boot).find(),
                        launcher + " did not start on the extensions frozen at build:\n" + boot);
            } else {
                // Vidocq BUG-20261115-05, Vauban BUG-20261115-07: every archive was processed at compile, and the
                // application layer sees their markers: the start runs no extension and has no record to load.
                assertTrue(WEAVING_ROW.matcher(boot).find(),
                        launcher + " printed no layer section of the detailed startup report:\n" + boot);
                assertFalse(CDI_EXTENSIONS_ROW.matcher(boot).find(),
                        launcher + " ran, or loaded, extensions that all ran at compile:\n" + boot);
            }
            assertFalse(boot.contains(GENERATE_ANOMALY),
                    launcher + " reports a build step it did not miss:\n" + boot);
            URI whoAmI = URI.create("http://127.0.0.1:" + boundPort(boot) + CONTEXT_PATH + "/whoami");
            try (HttpClient alice = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
                 HttpClient bob = HttpClient.newBuilder().cookieHandler(new CookieManager()).build()) {
                assertEquals(whoAmIAnswer("alice", 1), get(alice, whoAmI, "alice"));
                assertEquals(whoAmIAnswer("bob", 1), get(bob, whoAmI, "bob"));
                assertEquals(whoAmIAnswer("alice", 2), get(alice, whoAmI, "alice"));
            }
        } finally {
            stop(launcher, application);
        }
        String run = read(log);
        assertFalse(run.contains(RUNTIME_GENERATION), "the container generated classes at run time:\n" + run);
    }

    /**
     * Jakarta Security on the packaged path: in the project {@code vidocq create} writes with the Servlet and
     * Kerckhoffs extensions, the BASIC mechanism and the identity store of the application authenticate the requests
     * Foy serves. The build compatible extensions of Kerckhoffs are frozen by {@code vidocq:generate}, the packaged
     * application starts on that record, and the container generates no class at run time.
     */
    @Test
    void aPackagedSecurityApplicationAuthenticatesOnTheExtensionsFrozenAtBuild(@TempDir Path dir) throws Exception {
        String version = System.getProperty("it.version");
        ProjectScaffolder.scaffold(new ProjectSpec("demo-app", "com.acme", "com.acme.demo.app",
                Set.of("servlet-foy", "security-kerckhoffs"), null, false), dir, version, SILENT);
        Path project = dir.resolve("demo-app");
        useThisBuildsArtifacts(project);
        addTheSecuredServlet(project);

        String output = build(project, "create-security.log", "verify");
        List<String> warnings = BuildWarnings.warningsOf(output, outerRepositoryUrl());
        assertEquals(List.of(), warnings, "the created security project builds with warnings:\n" + output);
        assertTheBuildFrozeItsExtensions(project, output);
        String frozenRecord = Files.readString(project.resolve("target/classes/" + FROZEN_RECORD));
        for (String extension : List.of("io.vidocq.kerckhoffs.cdi.KerckhoffsExtension",
                "io.vidocq.kerckhoffs.foy.FoySecurityContextExtension")) {
            assertTrue(frozenRecord.contains(extension),
                    extension + " is not in the record of the build:\n" + frozenRecord);
        }

        Path launcher = project.resolve("target/demo-app-1.0.0-SNAPSHOT/bin/demo-app.sh");
        Path log = logs().resolve("create-security-run.log");
        Process application = launch(launcher, "-Dvidocq.http.host=127.0.0.1 -Dvidocq.http.port=0 " + ON_THE_RECORD)
                .redirectOutput(log.toFile()).start();
        try {
            String boot = awaitStarted(application, log);
            assertTrue(FROZEN_AT_BUILD.matcher(boot).find(),
                    launcher + " did not start on the extensions frozen at build:\n" + boot);
            URI secured = URI.create("http://127.0.0.1:" + boundPort(boot) + "/secured");
            try (HttpClient client = HttpClient.newHttpClient()) {
                assertEquals(401, client.send(HttpRequest.newBuilder(secured).GET().build(),
                        HttpResponse.BodyHandlers.ofString()).statusCode());
                HttpResponse<String> response = client.send(HttpRequest.newBuilder(secured)
                        .header("Authorization", "Basic " + java.util.Base64.getEncoder()
                                .encodeToString("alice:wonderland".getBytes(StandardCharsets.UTF_8)))
                        .GET().build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode(), response::body);
                assertEquals("alice|true", response.body());
            }
        } finally {
            stop(launcher, application);
        }
        String run = read(log);
        assertFalse(run.contains(RUNTIME_GENERATION), "the container generated classes at run time:\n" + run);
    }

    /**
     * Writes into the created project the BASIC mechanism, an identity store with one user ({@code alice}, role
     * {@code admin}) and a servlet protected for that role that answers with the caller of the injected security
     * context; the module reads what they use.
     */
    private static void addTheSecuredServlet(Path project) throws IOException {
        Path sources = project.resolve("src/main/java/com/acme/demo/app");
        Files.createDirectories(sources);
        Files.writeString(sources.resolve("Basic.java"), """
                package com.acme.demo.app;

                import jakarta.enterprise.context.Dependent;
                import jakarta.security.enterprise.authentication.mechanism.http.BasicAuthenticationMechanismDefinition;

                @BasicAuthenticationMechanismDefinition(realmName = "demo")
                @Dependent
                public class Basic {
                }
                """);
        Files.writeString(sources.resolve("UserStore.java"), """
                package com.acme.demo.app;

                import jakarta.enterprise.context.ApplicationScoped;
                import jakarta.security.enterprise.credential.Credential;
                import jakarta.security.enterprise.credential.UsernamePasswordCredential;
                import jakarta.security.enterprise.identitystore.CredentialValidationResult;
                import jakarta.security.enterprise.identitystore.IdentityStore;
                import java.util.Set;

                @ApplicationScoped
                public class UserStore implements IdentityStore {
                    @Override
                    public CredentialValidationResult validate(Credential credential) {
                        if (credential instanceof UsernamePasswordCredential login
                                && "alice".equals(login.getCaller()) && login.getPassword().compareTo("wonderland")) {
                            return new CredentialValidationResult("alice", Set.of("admin"));
                        }
                        return CredentialValidationResult.INVALID_RESULT;
                    }
                }
                """);
        Files.writeString(sources.resolve("SecuredServlet.java"), """
                package com.acme.demo.app;

                import jakarta.inject.Inject;
                import jakarta.security.enterprise.SecurityContext;
                import jakarta.servlet.annotation.HttpConstraint;
                import jakarta.servlet.annotation.ServletSecurity;
                import jakarta.servlet.annotation.WebServlet;
                import jakarta.servlet.http.HttpServlet;
                import jakarta.servlet.http.HttpServletRequest;
                import jakarta.servlet.http.HttpServletResponse;
                import java.io.IOException;

                @WebServlet("/secured")
                @ServletSecurity(@HttpConstraint(rolesAllowed = "admin"))
                public class SecuredServlet extends HttpServlet {
                    @Inject SecurityContext security;

                    @Override
                    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                        resp.setContentType("text/plain");
                        resp.getWriter().write(security.getCallerPrincipal().getName() + "|"
                                + security.isCallerInRole("admin"));
                    }
                }
                """);
        Path moduleInfo = project.resolve("src/main/java/module-info.java");
        String source = Files.readString(moduleInfo);
        String declaration = "module com.acme.demo.app {\n";
        assertTrue(source.startsWith(declaration), source);
        StringBuilder requires = new StringBuilder(declaration);
        for (String module : List.of("jakarta.cdi", "jakarta.inject", "jakarta.servlet", "jakarta.security",
                "io.vidocq.foy.api")) {
            if (!source.contains("requires " + module + ";")) {
                requires.append("    requires ").append(module).append(";\n");
            }
        }
        Files.writeString(moduleInfo, requires + source.substring(declaration.length()));
    }

    /**
     * Writes into the created project a servlet that answers with what the injected request, session and context
     * give, and a listener that uses the injected context while the application starts; the module reads what they
     * use.
     */
    private static void addTheWhoAmIServlet(Path project) throws IOException {
        Path sources = project.resolve("src/main/java/com/acme/demo/app");
        Files.createDirectories(sources);
        Files.writeString(sources.resolve("WhoAmIServlet.java"), """
                package com.acme.demo.app;

                import jakarta.inject.Inject;
                import jakarta.servlet.ServletContext;
                import jakarta.servlet.annotation.WebServlet;
                import jakarta.servlet.http.HttpServlet;
                import jakarta.servlet.http.HttpServletRequest;
                import jakarta.servlet.http.HttpServletResponse;
                import jakarta.servlet.http.HttpSession;
                import java.io.IOException;

                @WebServlet("/whoami")
                public class WhoAmIServlet extends HttpServlet {
                    @Inject HttpServletRequest request;
                    @Inject HttpSession session;
                    @Inject ServletContext context;

                    @Override
                    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                        Integer visits = (Integer) session.getAttribute("visits");
                        int visit = visits == null ? 1 : visits + 1;
                        session.setAttribute("visits", visit);
                        String same = session.getId().equals(req.getSession().getId()) ? "same session" : "another session";
                        resp.setContentType("text/plain");
                        resp.getWriter().write(String.join("|", request.getRequestURI(), request.getHeader("X-Caller"),
                                String.valueOf(visit), same, context.getContextPath(),
                                String.valueOf(context.getAttribute("whoami.started"))));
                    }
                }
                """);
        Files.writeString(sources.resolve("ContextStartListener.java"), """
                package com.acme.demo.app;

                import jakarta.inject.Inject;
                import jakarta.servlet.ServletContext;
                import jakarta.servlet.ServletContextEvent;
                import jakarta.servlet.ServletContextListener;
                import jakarta.servlet.annotation.WebListener;

                @WebListener
                public class ContextStartListener implements ServletContextListener {
                    @Inject ServletContext context;

                    @Override
                    public void contextInitialized(ServletContextEvent event) {
                        context.setAttribute("whoami.started", "started in " + context.getContextPath());
                    }
                }
                """);
        // What the servlets read, and io.vidocq.foy.api for the $$FoyComponent factories the Servlet codegen bundle
        // generates; another extension of the project may already have the module read one of them.
        Path moduleInfo = project.resolve("src/main/java/module-info.java");
        String source = Files.readString(moduleInfo);
        String declaration = "module com.acme.demo.app {\n";
        assertTrue(source.startsWith(declaration), source);
        StringBuilder requires = new StringBuilder(declaration);
        for (String module : List.of("jakarta.inject", "jakarta.servlet", "io.vidocq.foy.api")) {
            if (!source.contains("requires " + module + ";")) {
                requires.append("    requires ").append(module).append(";\n");
            }
        }
        Files.writeString(moduleInfo, requires + source.substring(declaration.length()));
        Files.writeString(project.resolve("src/main/resources/vidocq.properties"),
                "\nvidocq.servlet.context-path=" + CONTEXT_PATH + "\n", StandardOpenOption.APPEND);
    }

    /** What {@code WhoAmIServlet} answers to {@code caller} on its {@code visit}-th request of one session. */
    private static String whoAmIAnswer(String caller, int visit) {
        return String.join("|", CONTEXT_PATH + "/whoami", caller, String.valueOf(visit), "same session", CONTEXT_PATH,
                "started in " + CONTEXT_PATH);
    }

    /** {@code GET uri} with {@code X-Caller: caller}; returns the body of a 200. */
    private static String get(HttpClient client, URI uri, String caller) throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(uri).header("X-Caller", caller).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response::body);
        return response.body();
    }

    /** The port the default listener bound, from the line Chappe logs once it listens. */
    private static int boundPort(String boot) {
        Matcher started = LISTENING.matcher(boot);
        assertTrue(started.find(), "no start line of the default listener:\n" + boot);
        return Integer.parseInt(started.group(1));
    }

    /**
     * Waits for the application writing to {@code log} to print the last line of its boot; fails as soon as it ends
     * before, or after {@link #START_TIMEOUT}.
     *
     * @return what it printed until then
     */
    private static String awaitStarted(Process application, Path log) throws Exception {
        long deadline = System.nanoTime() + START_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            String out = read(log);
            if (out.contains(STARTED)) {
                return out;
            }
            if (application.waitFor(250, TimeUnit.MILLISECONDS)) {
                return fail("the application ended with " + application.exitValue() + " before it started:\n"
                        + read(log));
            }
        }
        return fail("the application did not start within " + START_TIMEOUT + ":\n" + read(log));
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
     * Vidocq BUG-20261115-05, Vauban BUG-20261115-07: every bean archive of the deployment was processed at compile,
     * so {@code vidocq:generate} has nothing to freeze, writes no record, and says why, without a warning.
     */
    private static void assertTheBuildHadNothingToFreeze(Path project, String output) {
        assertFalse(Files.exists(project.resolve("target/classes/" + FROZEN_RECORD)),
                "vidocq:generate froze extensions the compilation already ran:\n" + output);
        assertTrue(output.lines().anyMatch(RAN_AT_COMPILE::equals),
                "vidocq:generate did not say that every extension ran at compile:\n" + output);
    }

    /**
     * Starts what the build packaged and reads its detailed startup report: the start loaded what the build recorded
     * instead of running the build compatible extensions. The record is used only for the deployment it was written
     * for, so this fails when the packaged path is not the one {@code vidocq:generate} froze.
     *
     * <p>The application listens on a port the system picks (port 0), never on 8080, which another process of the
     * machine may hold, and only the process started here is stopped.
     *
     * @return what the application printed until it started
     */
    private static String assertStartsOnTheFrozenExtensions(Path launcher) throws Exception {
        Process application = launch(launcher, "-Dvidocq.http.port=0 " + ON_THE_RECORD).start();
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
        return boot;
    }

    /**
     * The launch of what the build packaged.
     *
     * @param options the system properties of this launch, as {@code -Dkey=value} separated by spaces
     */
    private static ProcessBuilder launch(Path launcher, String options) {
        ProcessBuilder command = new ProcessBuilder(launcher.toString()).redirectErrorStream(true);
        command.environment().put("JAVA_HOME", System.getProperty("java.home"));
        // The java launcher reads it itself, whatever the launcher: the one of a jlink image passes no JAVA_OPTS.
        command.environment().put("JDK_JAVA_OPTIONS", options);
        return command;
    }

    /**
     * Runs {@code mvn -ntp -B goals} in the created project, into {@code log} under the IT logs.
     *
     * @return what the build printed, once it ended with 0
     */
    private static String build(Path project, String log, String... goals) throws Exception {
        return build(project, Map.of(), log, goals);
    }

    /**
     * Runs {@code mvn -ntp -B goals} in the created project with {@code environment} added to this JVM's, into
     * {@code log} under the IT logs. Maven is named by its path, found on this JVM's {@code PATH}, so that an
     * environment's {@code PATH} needs no {@code mvn}; {@code JAVA_HOME} is this JVM's.
     *
     * @return what the build printed, once it ended with 0
     */
    private static String build(Path project, Map<String, String> environment, String log, String... goals)
            throws Exception {
        Path file = logs().resolve(log);
        List<String> command = new ArrayList<>(List.of(environment.containsKey("PATH") ? onThePath("mvn") : "mvn",
                "-ntp", "-B"));
        command.addAll(List.of(goals));
        String what = "mvn " + String.join(" ", goals);
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(project.toFile())
                .redirectErrorStream(true)
                .redirectOutput(file.toFile());
        if (!environment.isEmpty()) {
            builder.environment().put("JAVA_HOME", System.getProperty("java.home"));
            builder.environment().putAll(environment);
        }
        Process build = builder.start();
        if (!build.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
            build.destroyForcibly();
            fail(what + " did not end within " + TIMEOUT + ":\n" + read(file));
        }
        String output = read(file);
        assertEquals(0, build.exitValue(), () -> what + " failed:\n" + output);
        return output;
    }

    /**
     * Runs git in {@code dir}, with a throwaway identity, no signing and no hooks: the machine's own settings (a
     * signing key, the sign-off hooks of a workspace) are no part of the test.
     *
     * @return what git printed, trimmed, once it ended with 0
     */
    private static String git(Path dir, String... arguments) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git", "-C", dir.toString(), "-c", "user.name=it",
                "-c", "user.email=it@example.invalid", "-c", "commit.gpgsign=false", "-c", "core.hooksPath=/dev/null"));
        command.addAll(List.of(arguments));
        Process git = new ProcessBuilder(command).redirectErrorStream(true).start();
        String out = new String(git.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        String what = "git " + String.join(" ", arguments);
        assertTrue(git.waitFor(1, TimeUnit.MINUTES), what + " did not end");
        assertEquals(0, git.exitValue(), () -> what + " failed:\n" + out);
        return out;
    }

    /** A created application (REST, the parent or not) under {@code root}, with this build's artifacts. */
    private static Path application(Path root, boolean withParent) throws IOException {
        ProjectScaffolder.scaffold(new ProjectSpec("demo-app", "com.acme", "com.acme.demo.app",
                Set.of("rest-cassini"), null, false, false, withParent), root, System.getProperty("it.version"), SILENT);
        useThisBuildsArtifacts(root.resolve("demo-app"));
        return root;
    }

    /** {@link #application} in a git repository of its own, committed on {@code main}, {@code target/} ignored. */
    private static Path committedApplication(Path root, boolean withParent) throws IOException, InterruptedException {
        application(root, withParent);
        Files.writeString(root.resolve(".gitignore"), "target/\n");
        git(root, "init", "-q", "-b", "main");
        git(root, "add", "-A");
        git(root, "commit", "-q", "-m", "the application");
        return root;
    }

    /** The build identity the build wrote for the created application in {@code project}. */
    private static Properties identity(Path project) throws IOException {
        Properties identity = new Properties();
        try (Reader reader = Files.newBufferedReader(
                project.resolve("target/classes/META-INF/vidocq/build-info/demo-app.properties"))) {
            identity.load(reader);
        }
        return identity;
    }

    /**
     * A {@code PATH} of one directory, {@code dir}, with a link to every command of this JVM's {@code PATH} but
     * {@code git} and its {@code git-*} helpers: this machine without git.
     */
    private static String pathWithoutGit(Path dir) throws IOException {
        Files.createDirectories(dir);
        for (String entry : System.getenv("PATH").split(File.pathSeparator)) {
            Path directory = Path.of(entry.isEmpty() ? "." : entry);
            if (!Files.isDirectory(directory)) {
                continue;
            }
            try (Stream<Path> commands = Files.list(directory)) {
                for (Path command : commands.toList()) {
                    String name = command.getFileName().toString();
                    Path link = dir.resolve(name);
                    if (!name.equals("git") && !name.startsWith("git-") && Files.isExecutable(command)
                            && !Files.exists(link, LinkOption.NOFOLLOW_LINKS)) {
                        Files.createSymbolicLink(link, command.toAbsolutePath());
                    }
                }
            }
        }
        return dir.toString();
    }

    /**
     * Removes the links {@link #pathWithoutGit} made, before JUnit deletes the temporary directory: it warns about each
     * link that leads out of it.
     */
    private static void removeLinks(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> links = Files.list(dir)) {
            for (Path link : links.toList()) {
                Files.deleteIfExists(link);
            }
        }
    }

    /** The path of {@code command} on this JVM's {@code PATH}. */
    private static String onThePath(String command) {
        for (String entry : System.getenv("PATH").split(File.pathSeparator)) {
            Path candidate = Path.of(entry.isEmpty() ? "." : entry, command);
            if (Files.isExecutable(candidate)) {
                return candidate.toAbsolutePath().toString();
            }
        }
        throw new AssertionError("no " + command + " on the PATH: " + System.getenv("PATH"));
    }

    /** Whether a {@code git} command answers on this machine. */
    private static boolean gitAnswers() throws InterruptedException {
        try {
            Process git = new ProcessBuilder("git", "--version").redirectErrorStream(true).start();
            git.getInputStream().readAllBytes();
            return git.waitFor(1, TimeUnit.MINUTES) && git.exitValue() == 0;
        } catch (IOException _) {
            return false;
        }
    }

    /** Where the IT writes the logs of the builds and starts it runs, kept for a post-mortem. */
    private static Path logs() throws IOException {
        return Files.createDirectories(Path.of(System.getProperty("it.logs")));
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
        String url = outerRepositoryUrl();
        String template = Files.readString(Path.of(System.getProperty("it.settings.template")));
        Files.writeString(settings, template.replace("@localRepositoryUrl@", url));
        Files.writeString(project.resolve(".mvn/maven.config"),
                "-Dmaven.repo.local=" + System.getProperty("it.repo") + "\n--settings\n" + settings + "\n");
    }

    /** The outer local repository as the fixture's settings name it, and as Maven prints it in a warning. */
    private static String outerRepositoryUrl() {
        return Path.of(System.getProperty("it.outerRepository")).toUri().toString();
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
