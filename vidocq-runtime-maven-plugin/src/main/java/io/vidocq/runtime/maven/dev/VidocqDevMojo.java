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

import io.vidocq.runtime.devservices.host.ApplicationFiles;
import io.vidocq.runtime.devservices.host.DevServicesException;
import io.vidocq.runtime.devservices.host.DevServicesFlag;
import io.vidocq.runtime.devservices.host.DevServicesSession;
import io.vidocq.runtime.devservices.host.StateFile;
import io.vidocq.runtime.maven.ApplicationLaunch;
import io.vidocq.runtime.maven.ConsoleColors;
import io.vidocq.runtime.maven.JdwpAgent;
import io.vidocq.runtime.maven.VidocqRunMojo;
import io.vidocq.runtime.maven.dev.TestResults.Trigger;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResult;
import org.eclipse.aether.util.filter.DependencyFilterUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Vidocq dev mode — implementation of milestone <b>M1</b> from
 * {@code DEBUGMODE.md}, "Approach A: fast process-restart".
 *
 * <p>Workflow:
 * <ol>
 *   <li>Compose the same module-path the production launcher uses
 *       (target/classes + every runtime/compile artifact).</li>
 *   <li>Fork a child JVM with {@code -Dvidocq.profile=dev} and inherited I/O.</li>
 *   <li>Watch {@code src/main/{java,resources}} for changes.</li>
 *   <li>With continuous testing (#122), watch {@code src/test/{java,resources}} too and run the tests after every
 *       reload, or alone after a test-only change; see {@link ContinuousTesting}.</li>
 *   <li>We have a relevant change (debounced 250 ms by default), shell out
 *       {@code mvn process-classes}; on success, gracefully stop the child JVM
 *       and respawn it.</li>
 * </ol>
 *
 * <p>The Mojo is <b>blocking</b> — it keeps the terminal until the user hits
 * Ctrl+C, at which point a JVM shutdown hook kills the child cleanly. No state
 * survives a reload: every cycle is a "miniature production", which is why
 * Approach A honors Java Modules strict + static codegen with zero risk of class-loader
 * leaks.</p>
 *
 * <p><b>Not for use against TCK runners</b> ({@code cassini-tck}, {@code foy-tck}, …)
 * — those are detached Model 4.0.0 POMs and would not resolve the plugin.</p>
 */
@Mojo(name = "dev",
        defaultPhase = LifecyclePhase.NONE,
        requiresDependencyResolution = ResolutionScope.RUNTIME,
        threadSafe = false)
public class VidocqDevMojo extends AbstractMojo {

    /**
     * Prefix of the markers that say which dev service provided a key the child sees:
     * {@code vidocq.dev.provided.<key>=<provider id>}.
     */
    static final String DEV_PROVIDED_PREFIX = DevServicesSession.PROVIDED_PREFIX;

    /** The continuous-testing switch, in every source. */
    static final String CONTINUOUS_TESTING_KEY = "vidocq.dev.continuousTesting";

    /** How long the tests of a main change wait for the hot reload to answer. */
    static final Duration RELOAD_ACK_TIMEOUT = Duration.ofMinutes(2);

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /** The Maven session, for the {@code -Dvidocq.*} properties forwarded to the application. */
    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    /**
     * Java module containing the main class. Required — Vidocq apps are
     * always launched on the module path, never the class path.
     */
    @Parameter(property = "vidocq.mainModule", required = true)
    private String mainModule;

    /**
     * Fully-qualified main class. Optional: when omitted, the child JVM links
     * on the {@code ModuleMainClass} attribute baked into the app's
     * {@code module-info.class}.
     */
    @Parameter(property = "vidocq.mainClass")
    private String mainClass;

    /** Directories to watch, relative to the project base dir. */
    @Parameter(property = "vidocq.dev.watchDirs",
            defaultValue = "src/main/java,src/main/resources")
    private String watchDirs;

    /**
     * Test directories to watch, relative to the project base dir: a change there runs the tests again, without an
     * application reload. Only with continuous testing.
     */
    @Parameter(property = "vidocq.dev.testWatchDirs", defaultValue = "src/test/java,src/test/resources")
    private String testWatchDirs;

    /**
     * Continuous testing (#122): run the application's tests after every reload, results in the dev console's
     * {@code tests} panel. Unset by default, so that {@code vidocq.dev.continuousTesting} in the application's files
     * is read; then on when {@code src/test/java} exists. See {@link #continuousTestingEnabled}.
     */
    @Parameter(property = "vidocq.dev.continuousTesting")
    private Boolean continuousTesting;

    /** Debounce window in milliseconds — collapses bursts of editor saves. */
    @Parameter(property = "vidocq.dev.debounceMillis", defaultValue = "250")
    private long debounceMillis;

    /**
     * Grace period given to the child JVM after {@link Process#destroy()}
     * before {@link Process#destroyForcibly()} fires. Five seconds is plenty
     * for the screed drain in dev — production keeps the 30 s default.
     */
    @Parameter(property = "vidocq.dev.gracePeriodMillis", defaultValue = "5000")
    private long gracePeriodMillis;

    /** Extra JVM args passed verbatim to the child (split on whitespace). */
    @Parameter(property = "vidocq.dev.jvmArgs", defaultValue = "")
    private String extraJvmArgs;

    /**
     * Extra {@code -Dkey=value} system properties for the child. Format is the
     * usual comma-separated {@code key=value,key2=value2}. Comes <i>in addition</i>
     * to {@code vidocq.profile=dev} which is always set.
     */
    @Parameter(property = "vidocq.dev.systemProperties", defaultValue = "")
    private String extraSystemProperties;

    /** Profile string injected as {@code -Dvidocq.profile=…} in the child. */
    @Parameter(property = "vidocq.profile", defaultValue = "dev")
    private String profile;

    /**
     * Open a JDWP debug agent on the child JVM. On by default in dev mode — attach a remote
     * debugger (e.g. IntelliJ "Remote JVM Debug") to {@link #debugHost}:{@link #debugPort}. Disable
     * with {@code -Dvidocq.dev.debug=false}.
     */
    @Parameter(property = "vidocq.dev.debug", defaultValue = "true")
    private boolean debug;

    /** JDWP listen port for the debug agent. */
    @Parameter(property = "vidocq.dev.debugPort", defaultValue = "5005")
    private int debugPort;

    /**
     * The interface the debug agent listens on. The loopback interface by default: whoever reaches the agent
     * can run any code in the child JVM. {@code *} or {@code 0.0.0.0} opens it on every interface, an address
     * or a host name on that one — with a warning, since the debugger is then reachable from the network.
     */
    @Parameter(property = "vidocq.dev.debugHost", defaultValue = JdwpAgent.DEFAULT_HOST)
    private String debugHost;

    /**
     * Suspend the child JVM until a debugger attaches ({@code suspend=y}) — useful to debug boot
     * itself. Default {@code false}: the app starts immediately and you attach whenever.
     */
    @Parameter(property = "vidocq.dev.debugSuspend", defaultValue = "false")
    private boolean debugSuspend;

    /**
     * Provision dev-mode services (Postgres, Keycloak, …) discovered via the {@code DevService}
     * SPI from this plugin's {@code <dependencies>}. Disable with {@code -Dvidocq.dev.devServices=false}, or with
     * {@code vidocq.dev.devServices=false} in the application's own files. Unset by default, so that the files are
     * read when neither {@code -D} nor the goal's configuration gives a value; see {@link #devServicesEnabled}.
     */
    @Parameter(property = "vidocq.dev.devServices")
    private Boolean devServices;

    /**
     * Universal-loader mode (default): the application's classes stay off the module
     * path and boot inside a child module layer defined by the Vauban class loader —
     * classes are woven at definition, no instrumentation agent. Set to {@code false}
     * to restore the legacy everything-on-the-module-path launch.
     */
    @Parameter(defaultValue = "true", property = "vidocq.dev.layer")
    private boolean layerMode;

    /**
     * In-JVM hot reload (default, requires {@link #layerMode}): after a successful
     * recompile the child JVM is signalled (reload file touch) and re-creates its
     * application module layer in place — no process respawn, warm JIT, debugger and
     * dev services survive. Set to {@code false} to restore the stop-and-respawn cycle.
     */
    @Parameter(defaultValue = "true", property = "vidocq.dev.hotReload")
    private boolean hotReload;

    @Parameter(defaultValue = "${project.build.outputDirectory}", readonly = true)
    private File classesDir;

    @Parameter(defaultValue = "${project.basedir}", readonly = true)
    private File baseDir;

    @Parameter(defaultValue = "${project.build.directory}", readonly = true)
    private File buildDir;

    /** This plugin's own resolved dependencies, keyed {@code groupId:artifactId} — {@link DevServicesExtensionJar}
     *  looks the dev services extension jar up here to add it to the child's module path. */
    @Parameter(defaultValue = "${plugin.artifactMap}", readonly = true)
    private Map<String, Artifact> pluginArtifactMap;

    /** Aether repository session, for {@link #resolveRuntime}'s resolution of each companion (Vidocq/vidocq#143). */
    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true, required = true)
    private RepositorySystemSession repoSession;

    /** The project's own remote repositories, for {@link #resolveRuntime}. */
    @Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true)
    private List<RemoteRepository> remoteRepos;

    @Component
    private RepositorySystem repoSystem;

    /**
     * This plugin's own version, for resolving the dev console and its SPI through Aether ({@link
     * #resolveConsoleArtifact}): those artifacts are released in lockstep with this plugin, and are never a
     * {@code vidocq-runtime-maven-plugin} dependency (see the note in this module's {@code pom.xml}).
     */
    @Parameter(defaultValue = "${plugin.version}", readonly = true, required = true)
    private String pluginVersion;

    @Override
    public void execute() throws MojoExecutionException {
        Path projectDir = baseDir.toPath();
        List<Path> watch = parseDirs(projectDir, watchDirs);
        List<Path> modulePath = buildModulePath();
        List<Path> appPath = buildAppPath();
        Map<String, String> sysProps = buildSystemProperties();
        List<String> jvmArgs = buildJvmArgs();

        // In-JVM hot reload: the child watches this file's mtime and swaps its
        // application layer when we touch it (after a successful recompile).
        boolean inJvmReload = layerMode && hotReload;
        Path reloadFile = buildDir.toPath().resolve(".vidocq-dev-reload");
        if (inJvmReload) {
            try {
                Files.createDirectories(reloadFile.getParent());
                Files.writeString(reloadFile, "0\n");
            } catch (IOException e) {
                throw new MojoExecutionException("Cannot create the reload signal file "
                        + reloadFile, e);
            }
            sysProps.put("vidocq.dev.reload.file", reloadFile.toString());
        }

        // The dev module path uses the original dependency jars: re-attach the classes
        // that vidocq:generate parked for scanned dependencies (target/vidocq-patches)
        // to their owning module, exactly like the packaging goals do by enrichment.
        try {
            List<String> patchArgs = ApplicationLaunch.patchModuleArgs(project, buildDirPath());
            if (!patchArgs.isEmpty()) {
                jvmArgs = new ArrayList<>(jvmArgs);
                jvmArgs.addAll(patchArgs);
                getLog().info("JPMS: " + patchArgs.size() / 2
                        + " --patch-module option(s) added for generated classes");
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to compute --patch-module options", e);
        }

        getLog().info("Vidocq dev — main module : " + mainModule
                + (mainClass != null && !mainClass.isBlank() ? ("/" + mainClass) : ""));
        if (layerMode) {
            getLog().info("Universal-loader mode: app classes boot in a Vauban-defined"
                    + " module layer (-Dvidocq.dev.layer=false for the legacy launch)");
        }
        getLog().info("Watching: " + watch);
        getLog().info("Module path entries: " + modulePath.size());
        logDebugAgent();

        // Provision dev-mode services (Postgres, Keycloak, …) ONCE, before the first fork. Their
        // connection coordinates are folded into the child's system properties; an explicit -D or a
        // vidocq.dev.systemProperties entry always wins (putIfAbsent), and each key the child does get from
        // a provider is marked vidocq.dev.provided.<key>=<id>. The containers live for the whole session —
        // source reloads respawn the child but never touch them.
        DevServicesSession devs = null;
        Function<String, Optional<String>> applicationFiles = ApplicationFiles.of(classesDir.toPath());
        if (devServicesEnabled(applicationFiles)) {
            // Resolved before open(): a missing extension/devconsole-spi jar must abort before any
            // container is started, never leave a running session with nothing left to close it.
            List<Path> extensionJars = DevServicesExtensionJar.resolve(pluginArtifactMap, project.getArtifacts());
            try {
                devs = DevServicesSession.open("vidocq:dev", projectDir, sysProps, applicationFiles,
                        System.getLogger("vidocq.dev.devservices"));
            } catch (DevServicesException e) {
                throw new MojoExecutionException(e.getMessage(), e);
            }
            foldDevServiceProperties(sysProps, devs.injected(), devs.providers());
            sysProps.putIfAbsent(StateFile.PROPERTY, devs.stateFile().toAbsolutePath().toString());
            modulePath.addAll(extensionJars);
        }
        final DevServicesSession devServicesRef = devs;

        // Dev tools (Vidocq/vidocq#143): the console, then each extension's -dev companion, on the child's module
        // path only. No binary ever contains them. devToolsOnPath is seeded by the console step with the keys it
        // actually added, so a companion whose own transitive dependencies include the SPI never adds it again
        // (spec §5.3 "never twice").
        Set<String> devToolsOnPath = new HashSet<>();
        modulePath.addAll(resolveDevConsole(devToolsOnPath));
        modulePath.addAll(DevModules.collect(project.getArtifacts(), this::resolveRuntime, getLog()::warn,
                getLog()::info, devToolsOnPath));

        // Continuous testing (#122): the test directories are watched too, the child's dev console learns where
        // the results are, and every run gets the dev session's keys, so the tests use its containers.
        boolean testsOn = continuousTestingEnabled(projectDir, applicationFiles);
        List<Path> testWatch = testsOn ? parseDirs(projectDir, testWatchDirs) : List.of();
        ContinuousTesting testing = null;
        TestRequestFile requests = null;
        if (testsOn) {
            Path results = buildDirPath().resolve(TestResultsFile.FILE_NAME);
            sysProps.put(TestResultsFile.PROPERTY, results.toAbsolutePath().toString());
            Map<String, String> testProps = new LinkedHashMap<>();
            if (devs != null) {
                devs.foldInto(testProps);
            }
            ContinuousTesting started = ContinuousTesting.forProject(projectDir, buildDirPath(), testProps,
                    result -> getLog().info(TestSummaryPrinter.headline(result)), getLog()::warn);
            testing = started;
            try {
                requests = TestRequestFile.poll(results.resolveSibling(TestRequestFile.FILE_NAME), trigger -> {
                    String outcome = started.request(trigger);
                    if (!ContinuousTesting.QUEUED.equals(outcome)) {
                        getLog().info("Tests: " + outcome);
                    }
                }, getLog()::warn);
            } catch (IOException e) {
                closeDevServices(devServicesRef);
                throw new MojoExecutionException("Cannot watch the test request file", e);
            }
            getLog().info("Continuous testing: on, watching " + testWatch
                    + " (-D" + CONTINUOUS_TESTING_KEY + "=false to turn it off)");
        }
        final ContinuousTesting testingRef = testing;
        final TestRequestFile requestsRef = requests;

        // The atomic reference lets the shutdown hook (running on a separate
        // thread) see the latest spawned child, no matter how many reload
        // cycles we have been through.
        AtomicReference<ChildJvm> currentChild = new AtomicReference<>();
        Thread mainThread = Thread.currentThread();

        Thread hook = new Thread(() -> {
            mainThread.interrupt();
            closeTesting(testingRef, requestsRef);
            ChildJvm c = currentChild.get();
            if (c != null) {
                try {
                    c.stop(Duration.ofMillis(gracePeriodMillis));
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            closeDevServices(devServicesRef);
        }, "vidocq-dev-shutdown");
        Runtime.getRuntime().addShutdownHook(hook);

        RecompileRunner recompile = new RecompileRunner(projectDir);
        ChildLaunch launch = new ChildLaunch(modulePath, appPath, jvmArgs, sysProps, projectDir);

        try (SourceWatcher watcher = SourceWatcher.on(watch, testWatch, Duration.ofMillis(debounceMillis))) {
            ChildJvm child = newChild(launch);
            long pid = child.start();
            currentChild.set(child);
            getLog().info("Child JVM started, pid=" + pid + ". Listening for changes (Ctrl+C to stop).");
            if (testingRef != null) {
                // The first run, once the first boot completed (spec §7: the panel shows a result at once).
                testingRef.start();
                testingRef.changed(Trigger.RUN_ALL, inJvmReload
                        ? ReloadAck.gate(reloadFile, ReloadAck.stamp(reloadFile), RELOAD_ACK_TIMEOUT,
                                child::isAlive, getLog()::warn)
                        : TestControl.ReadyGate.NOW);
            }

            while (!Thread.currentThread().isInterrupted()) {
                SourceWatcher.Change change = watcher.awaitChanges();
                if (change == null) {
                    break; // watcher closed
                }
                onChange(change, testingRef,
                        () -> recompileAndReload(recompile, launch, currentChild, reloadFile, inJvmReload));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            getLog().info("Dev mode interrupted — shutting down.");
        } catch (IOException e) {
            throw new MojoExecutionException("Dev mode initialisation failed", e);
        } finally {
            closeTesting(testingRef, requestsRef);
            // The shutdown hook will have done it already on Ctrl+C, but if we
            // fell through because the watcher closed normally we still need to
            // tear the child down here.
            ChildJvm c = currentChild.getAndSet(null);
            if (c != null && c.isAlive()) {
                try {
                    c.stop(Duration.ofMillis(gracePeriodMillis));
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            // Stop the dev-mode containers (idempotent — the hook may already have run on Ctrl+C).
            closeDevServices(devServicesRef);
            // Avoid IllegalStateException if the JVM is mid-shutdown.
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException ignored) {
                // shutdown already in progress
            }
        }
    }

    /** What every child JVM of this session is started with. */
    private record ChildLaunch(List<Path> modulePath, List<Path> appPath, List<String> jvmArgs,
            Map<String, String> sysProps, Path projectDir) {}

    private ChildJvm newChild(ChildLaunch launch) {
        return ChildJvm.of(launch.modulePath(), launch.appPath(), mainModule, mainClass, launch.jvmArgs(),
                launch.sysProps(), launch.projectDir());
    }

    /** Recompiles and reloads the application: what the tests of this change wait for. */
    @FunctionalInterface
    interface Reload {

        /** @return the gate that opens once the application booted again, or empty when nothing was reloaded */
        Optional<TestControl.ReadyGate> run() throws IOException, InterruptedException;
    }

    /** Reads the stamp of the reload signal just written; {@link ReloadAck#stamp} in production. */
    @FunctionalInterface
    interface StampReader {
        long stamp(Path reloadFile) throws IOException;
    }

    /**
     * Signals a hot reload: the gate the tests of this change wait for, or empty when the signal could not be written,
     * in which case the caller respawns the child. Once written, the child reloads whatever happens next: a stamp
     * that cannot be read then only costs the wait for the reload, the tests starting at once, never a second reload
     * by a respawn (#138).
     */
    static Optional<TestControl.ReadyGate> signalHotReload(Path reloadFile, StampReader stamps,
            BooleanSupplier childAlive, Consumer<String> warn) {
        try {
            Files.writeString(reloadFile, System.nanoTime() + "\n");
        } catch (IOException e) {
            warn.accept("Cannot signal hot reload (" + e.getMessage() + ") — falling back to a respawn.");
            return Optional.empty();
        }
        try {
            long stamp = stamps.stamp(reloadFile);
            return Optional.of(ReloadAck.gate(reloadFile, stamp, RELOAD_ACK_TIMEOUT, childAlive, warn));
        } catch (IOException e) {
            warn.accept("Tests: cannot read the time of the reload signal (" + e.getMessage()
                    + "); the tests start without waiting for the reload");
            return Optional.of(TestControl.ReadyGate.NOW);
        }
    }

    /**
     * One change of the source loop (spec §2.2). A test-only change runs the tests, and nothing is reloaded. A main
     * change first stops the run in flight and holds the requests, so that no test Maven races the recompile. It then
     * recompiles and reloads, and runs the tests once the reload completed; a failed recompile runs none and lets the
     * held requests run.
     *
     * @param tests {@code null} without continuous testing
     */
    static void onChange(SourceWatcher.Change change, TestControl tests, Reload reload)
            throws IOException, InterruptedException {
        if (!change.main()) {
            if (tests != null && change.test()) {
                tests.changed(Trigger.TEST_CHANGE, TestControl.ReadyGate.NOW);
            }
            return;
        }
        if (tests != null) {
            tests.interrupt();
        }
        Optional<TestControl.ReadyGate> booted = reload.run();
        if (tests == null) {
            return;
        }
        if (booted.isPresent()) {
            tests.changed(Trigger.CHANGE, booted.get());
        } else {
            tests.release();
        }
    }

    private Optional<TestControl.ReadyGate> recompileAndReload(RecompileRunner recompile, ChildLaunch launch,
            AtomicReference<ChildJvm> currentChild, Path reloadFile, boolean inJvmReload)
            throws IOException, InterruptedException {
        long t0 = System.nanoTime();
        getLog().info("Changes detected — recompiling...");
        int rc;
        try {
            rc = recompile.run();
        } catch (IOException e) {
            getLog().warn("Recompile invocation failed: " + e.getMessage());
            return Optional.empty();
        }
        if (rc != 0) {
            getLog().warn("Compile failed (exit " + rc + "); keeping previous JVM up.");
            return Optional.empty();
        }
        ChildJvm child = currentChild.get();
        if (inJvmReload && child.isAlive()) {
            // Signal the child: it re-creates its application layer in place.
            Optional<TestControl.ReadyGate> signalled =
                    signalHotReload(reloadFile, ReloadAck::stamp, child::isAlive, getLog()::warn);
            if (signalled.isPresent()) {
                getLog().info("Hot reload signalled after " + (System.nanoTime() - t0) / 1_000_000
                        + " ms (in-JVM layer swap).");
                return signalled;
            }
        }
        child.stop(Duration.ofMillis(gracePeriodMillis));
        ChildJvm next = newChild(launch);
        long pid = next.start();
        currentChild.set(next);
        getLog().info("Reloaded in " + (System.nanoTime() - t0) / 1_000_000 + " ms (pid=" + pid + ").");
        return Optional.of(TestControl.ReadyGate.NOW);
    }

    /** Comma-separated directories, relative to {@code projectDir}; blanks skipped. Shared with {@code vidocq:test}. */
    static List<Path> parseDirs(Path projectDir, String csv) {
        List<Path> result = new ArrayList<>();
        for (String s : csv.split(",")) {
            String trimmed = s.trim();
            if (!trimmed.isEmpty()) {
                result.add(projectDir.resolve(trimmed));
            }
        }
        return result;
    }

    /**
     * Build the module path the child JVM will be launched against. Layout
     * matches what {@link io.vidocq.runtime.maven.VidocqPackageMojo} ships in
     * {@code lib/}: the freshly compiled project classes plus every resolved
     * runtime/compile artifact.
     */
    private List<Path> buildModulePath() {
        // In layer mode the application classes travel through -Dvidocq.app.path instead: a module must
        // not be on both paths.
        return ApplicationLaunch.modulePath(project, buildDirPath(), classesDir.toPath(), layerMode,
                jar -> getLog().info("dev: using modularized copy of " + jar.getFileName()));
    }

    /**
     * {@code ${project.build.directory}}, falling back to the parent of the (always
     * injected) classes directory ({@code target/classes} → {@code target}) when Maven
     * did not inject it.
     */
    private Path buildDirPath() {
        return (buildDir != null ? buildDir : classesDir.getParentFile()).toPath();
    }

    /** The application archives of the layer mode: the project's own build output. */
    private List<Path> buildAppPath() {
        return ApplicationLaunch.appPath(classesDir.toPath(), layerMode);
    }

    /**
     * The dev console and its SPI (Vidocq/vidocq#143), resolved through Aether under this plugin's own
     * version, unless the application already has both or lacks Chappe. Never a {@code vidocq-runtime-maven-plugin}
     * dependency: {@code vidocq-runtime-devconsole-extension} sits inside the {@code vidocq-runtime-extensions}
     * reactor tree, whose parent pom activates this very plugin (checkpom) as a build tool on every extension —
     * a compile/runtime edge back onto it from here would make the reactor cyclic.
     *
     * <p>A jar that cannot be resolved (offline, or the local repository was never populated with this plugin's
     * own release) costs the console only: a warning is logged and {@code vidocq:dev} continues without it, the
     * same graceful degradation {@link DevModules#collect} gives an unresolvable companion. It never fails the
     * goal.</p>
     *
     * @param addedKeys collects the {@code groupId:artifactId} of every jar actually added, so the caller can seed
     *                  {@link DevModules#collect} with them (spec §5.3 "never twice")
     */
    private List<Path> resolveDevConsole(Set<String> addedKeys) {
        if (!DevConsoleJars.needsConsole(project.getArtifacts())) {
            getLog().info("Dev tools: no dev console, it needs vidocq-runtime-chappe-webserver-extension");
            return List.of();
        }
        Map<String, Artifact> consoleArtifacts = new LinkedHashMap<>();
        for (String key : List.of(DevConsoleJars.CONSOLE_KEY, DevConsoleJars.SPI_KEY)) {
            Artifact artifact = resolveConsoleArtifact(key);
            if (artifact == null) {
                // A partial console (e.g. missing its SPI) would fail the child's boot — skip it entirely.
                return List.of();
            }
            consoleArtifacts.put(key, artifact);
        }
        return DevConsoleJars.resolve(consoleArtifacts, project.getArtifacts(), getLog()::info, addedKeys);
    }

    /**
     * {@code key} ({@code groupId:artifactId}) at {@link #pluginVersion}, resolved through Aether as a jar, or
     * {@code null} — with a warning, never a build failure — when it cannot be resolved (e.g. offline on a first
     * run before {@code vidocq-runtime-devconsole-extension} was ever installed locally).
     */
    private Artifact resolveConsoleArtifact(String key) {
        int colon = key.indexOf(':');
        String groupId = key.substring(0, colon);
        String artifactId = key.substring(colon + 1);
        ArtifactRequest request = new ArtifactRequest();
        request.setArtifact(new DefaultArtifact(groupId, artifactId, "jar", pluginVersion));
        request.setRepositories(remoteRepos);
        try {
            ArtifactResult result = repoSystem.resolveArtifact(repoSession, request);
            Artifact resolved = new org.apache.maven.artifact.DefaultArtifact(groupId, artifactId, pluginVersion,
                    "runtime", "jar", "", new DefaultArtifactHandler("jar"));
            resolved.setFile(result.getArtifact().getFile());
            return resolved;
        } catch (ArtifactResolutionException e) {
            getLog().warn("Dev tools: cannot resolve " + key + ":" + pluginVersion + " (the dev console); vidocq:dev"
                    + " continues without it. Run the build once online, or mvn -U.");
            return null;
        }
    }

    /** {@code coordinates} and its runtime dependencies, through Maven's own resolution and repositories. */
    private List<DevModules.Resolved> resolveRuntime(String coordinates) throws Exception {
        Dependency root = new Dependency(new DefaultArtifact(coordinates), "runtime");
        CollectRequest collect = new CollectRequest(root, remoteRepos);
        DependencyRequest request = new DependencyRequest(collect, DependencyFilterUtils.classpathFilter("runtime"));
        List<DevModules.Resolved> jars = new ArrayList<>();
        DependencyResult result = repoSystem.resolveDependencies(repoSession, request);
        for (ArtifactResult artifactResult : result.getArtifactResults()) {
            org.eclipse.aether.artifact.Artifact artifact = artifactResult.getArtifact();
            jars.add(new DevModules.Resolved(artifact.getGroupId() + ":" + artifact.getArtifactId(),
                    artifact.getFile().toPath()));
        }
        return jars;
    }

    /**
     * Adds the {@code -Dvidocq.*} properties of the Maven command line that configure the application — the
     * same set {@code vidocq:run} forwards ({@link VidocqRunMojo#forwarded}). The child is another JVM, so
     * {@code mvn vidocq:dev -Dvidocq.devconsole.port=9000} would otherwise never reach it. What the child
     * already gets wins, a {@code vidocq.dev.systemProperties} entry included, as it does for {@code vidocq:run};
     * it runs before the colour policy is chosen, so an explicit {@code -Dvidocq.console.color} is seen there.
     */
    static void forwardCommandLine(Map<String, String> props, Properties userProperties) {
        VidocqRunMojo.forwarded(userProperties).forEach(props::putIfAbsent);
    }

    private Map<String, String> buildSystemProperties() {
        Map<String, String> props = new LinkedHashMap<>();
        props.put("vidocq.profile", profile);
        if (extraSystemProperties != null && !extraSystemProperties.isBlank()) {
            for (String pair : extraSystemProperties.split(",")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                props.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
            }
        }
        forwardCommandLine(props, session == null ? new Properties() : session.getUserProperties());
        // The child inherits Maven's streams, so it inherits Maven's colour policy: without this, an IDE
        // Maven console (no TTY, no idea_rt.jar agent) turns the runtime's colours off while Maven's own
        // lines stay coloured.
        ConsoleColors.forChild(props.containsKey(ConsoleColors.COLOR_KEY)).ifPresent(choice -> {
            props.put(ConsoleColors.COLOR_KEY, choice.mode());
            getLog().debug("dev: " + choice.logLine());
        });
        return props;
    }

    /**
     * JVM args for the child: the verbatim {@code extraJvmArgs} plus, when {@link #debug} is on, a
     * JDWP agent ({@code server=y}, suspend per {@link #debugSuspend}, listening on
     * {@link #debugHost}:{@link #debugPort}).
     */
    private List<String> buildJvmArgs() {
        List<String> args = splitArgs(extraJvmArgs);
        if (debug) {
            args.add(debugAgent().argument());
        }
        return args;
    }

    private JdwpAgent debugAgent() {
        return new JdwpAgent(debugHost, debugPort, debugSuspend);
    }

    /**
     * Announces the debug agent, when {@link #debug} is on, and warns when its host lets other machines reach
     * it.
     */
    // package-private for the tests of the warning.
    void logDebugAgent() {
        if (!debug) {
            return;
        }
        JdwpAgent agent = debugAgent();
        getLog().info("Debug agent (JDWP) on " + agent.where()
                + (debugSuspend ? " — child suspends until a debugger attaches" : " — attach any time"));
        agent.exposure("vidocq.dev.debugHost").ifPresent(getLog()::warn);
    }

    private static List<String> splitArgs(String raw) {
        if (raw == null || raw.isBlank()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(Arrays.asList(raw.trim().split("\\s+")));
    }

    /**
     * Folds the dev services' properties into the child's system properties, and marks each key the child gets
     * from a provider with {@code vidocq.dev.provided.<key>=<provider id>}, for example
     * {@code vidocq.dev.provided.vidocq.pool.audit.url=postgres}.
     *
     * <p>A value the child already gets, such as a {@code vidocq.dev.systemProperties} entry, always wins, and its
     * key is not marked: the child does not see the provider's value there. The marker is how the
     * application tells a dev-service datasource from a hand-set one, and only says who provided the key, never what:
     * it is read like any other configuration, and {@code vidocq.dev.*} is exempt from the configuration key audit.</p>
     */
    // package-private for the unit test.
    static void foldDevServiceProperties(Map<String, String> sysProps, Map<String, String> injected,
            Map<String, String> providers) {
        DevServicesSession.fold(injected, providers, sysProps);
    }

    /**
     * {@code vidocq.dev.devServices}, first match wins (spec §5, {@link DevServicesFlag}): the explicit value — a
     * {@code -D} or the goal's configuration — then the application's own files, then on.
     */
    // package-private for the unit test.
    boolean devServicesEnabled(Function<String, Optional<String>> files) throws MojoExecutionException {
        try {
            return DevServicesFlag.enabled(Optional.ofNullable(devServices).map(String::valueOf), files, true);
        } catch (IllegalArgumentException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }

    /**
     * {@code vidocq.dev.continuousTesting}, first match wins (spec §2.1), as {@link #devServicesEnabled}: the explicit
     * value, then the application's own files, then on when the project has {@code src/test/java}.
     */
    // package-private for the unit test.
    boolean continuousTestingEnabled(Path projectDir, Function<String, Optional<String>> files)
            throws MojoExecutionException {
        try {
            return DevServicesFlag.enabled(CONTINUOUS_TESTING_KEY,
                    Optional.ofNullable(continuousTesting).map(String::valueOf), files,
                    Files.isDirectory(projectDir.resolve("src/test/java")));
        } catch (IllegalArgumentException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }

    /**
     * Stops continuous testing: the request polling, then the run in flight, cancelled. Both are idempotent; the
     * lock makes the shutdown hook and {@code finally} wait for each other, as {@link #closeDevServices} does.
     */
    synchronized void closeTesting(ContinuousTesting testing, TestRequestFile requests) {
        if (requests != null) {
            requests.close();
        }
        if (testing != null) {
            testing.close();
        }
    }

    /**
     * Closes {@code session} (if not {@code null}), {@code synchronized} on this mojo instance: the shutdown
     * hook and {@code execute()}'s own {@code finally} block both call this, and {@link
     * DevServicesSession#close()} only guards itself against running twice — its second caller returns
     * immediately, without waiting for the first call to actually finish stopping the containers. The lock
     * here forces the second caller to wait until the first one's {@code close()} has fully returned, so
     * neither the hook nor {@code finally} can move on (and let the JVM believe shutdown is complete) while a
     * container is still being torn down.
     */
    // package-private for the unit test of the close-exactly-once wiring.
    synchronized void closeDevServices(DevServicesSession session) {
        if (session != null) {
            session.close();
        }
    }

    // Package-private accessors used in unit tests — keep at the bottom so the
    // execute() flow is the first thing a reader sees.
    void setProject(MavenProject project) { this.project = project; }
    void setMainModule(String mainModule) { this.mainModule = mainModule; }
    void setMainClass(String mainClass) { this.mainClass = mainClass; }
    void setBaseDir(File baseDir) { this.baseDir = baseDir; }
    void setBuildDir(File buildDir) { this.buildDir = buildDir; }
    void setClassesDir(File classesDir) { this.classesDir = classesDir; }
    void setDevServices(Boolean devServices) { this.devServices = devServices; }
    void setContinuousTesting(Boolean continuousTesting) { this.continuousTesting = continuousTesting; }
    void setExtraSystemProperties(String s) { this.extraSystemProperties = s; }
    void setProfile(String profile) { this.profile = profile; }
    Map<String, String> debugSystemProperties() { return buildSystemProperties(); }
    void setExtraJvmArgs(String s) { this.extraJvmArgs = s; }
    void setDebugOptions(boolean debug, int port, boolean suspend) {
        this.debug = debug;
        this.debugPort = port;
        this.debugSuspend = suspend;
    }
    void setDebugHost(String debugHost) { this.debugHost = debugHost; }
    List<String> debugJvmArgs() { return buildJvmArgs(); }
}
