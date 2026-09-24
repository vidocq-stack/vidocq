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
package io.vidocq.runtime.maven;

import io.vidocq.runtime.devservices.host.ApplicationFiles;
import io.vidocq.runtime.devservices.host.DevServicesException;
import io.vidocq.runtime.devservices.host.DevServicesSession;
import io.vidocq.runtime.devservices.host.StateFile;
import io.vidocq.runtime.maven.dev.ChildJvm;
import io.vidocq.runtime.maven.dev.DevServicesExtensionJar;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Runs the application once, in a forked JVM, the way the production launcher does — and the way an
 * IDE should: the goal forks the lifecycle up to {@code process-classes}, so {@code mvn vidocq:run}
 * alone compiles the module <em>and</em> runs {@code vidocq:generate}, whose bean index covers the beans
 * of the dependency jars. An IDE Run that builds with the IDE never runs {@code vidocq:generate} and the
 * server answers 404 (Vidocq/vidocq#83); a Run that is a Maven run of this goal cannot.
 *
 * <p>The child JVM has exactly the shape of the one {@code vidocq:dev} forks — module path, resolved
 * {@code ALL-MODULE-PATH}, application classes handed to the runtime through {@code -Dvidocq.app.path},
 * inherited I/O — without the source watcher, the reload file and {@code -Dvidocq.profile=dev}. The goal
 * blocks until the application exits, propagates its exit code, and stops it on Ctrl+C.
 *
 * <pre>mvn vidocq:run
 * mvn vidocq:run -Dvidocq.run.debug=true
 * mvn vidocq:run -Dvidocq.run.args="--port 8081"</pre>
 */
@Mojo(name = "run",
        defaultPhase = LifecyclePhase.NONE,
        requiresDependencyResolution = ResolutionScope.RUNTIME,
        requiresDirectInvocation = true,
        threadSafe = true)
@Execute(phase = LifecyclePhase.PROCESS_CLASSES)
public class VidocqRunMojo extends AbstractMojo {

    private static final String PREFIX = "Vidocq run: ";

    /**
     * Command-line properties that configure the build, never the application: they stay in the Maven
     * JVM. Everything else under {@code vidocq.} is forwarded to the child (see {@link #forwarded}).
     */
    private static final List<String> BUILD_PREFIXES = List.of("vidocq.run.", "vidocq.dev.", "vidocq.idea.",
            "vidocq.docker.", "vidocq.jlink.", "vidocq.checkpom.", "vidocq.moduleinfo.", "vidocq.package.");

    private static final List<String> BUILD_KEYS = List.of("vidocq.mainModule", "vidocq.mainClass",
            "vidocq.appName", "vidocq.appVersion", "vidocq.appDescription", "vidocq.compress", "vidocq.distDir",
            "vidocq.distName", "vidocq.icon", "vidocq.installerDir", "vidocq.jpackageType", "vidocq.jvmArgs",
            "vidocq.launcher", "vidocq.runtimeImage", "vidocq.scriptName", "vidocq.stripDebug", "vidocq.vendor");

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    /**
     * Java module containing the main class. Required — Vidocq apps are always launched on the module
     * path, never the class path.
     */
    @Parameter(property = "vidocq.mainModule", required = true)
    private String mainModule;

    /**
     * Fully-qualified main class. Optional: when omitted, the runtime links on the
     * {@code ModuleMainClass} attribute baked into the application's {@code module-info.class}.
     */
    @Parameter(property = "vidocq.mainClass")
    private String mainClass;

    /** Extra JVM args passed verbatim to the child (split on whitespace). */
    @Parameter(property = "vidocq.run.jvmArgs", defaultValue = "")
    private String extraJvmArgs;

    /** Application arguments, appended after the main module (split on whitespace). */
    @Parameter(property = "vidocq.run.args", defaultValue = "")
    private String appArgs;

    /**
     * Extra {@code -Dkey=value} system properties for the child, as {@code key=value,key2=value2}. A
     * {@code -Dvidocq.*} on the Maven command line reaches the application by itself.
     */
    @Parameter(property = "vidocq.run.systemProperties", defaultValue = "")
    private String extraSystemProperties;

    /**
     * Open a JDWP debug agent on the child JVM and print its address. Off by default — unlike
     * {@code vidocq:dev}, {@code vidocq:run} is also how the application runs in CI and in scripts.
     */
    @Parameter(property = "vidocq.run.debug", defaultValue = "false")
    private boolean debug;

    /** JDWP listen port for the debug agent. */
    @Parameter(property = "vidocq.run.debug.port", defaultValue = "5005")
    private int debugPort;

    /**
     * The interface the debug agent listens on. The loopback interface by default: whoever reaches the agent
     * can run any code in the child JVM. {@code *} or {@code 0.0.0.0} opens it on every interface, an address
     * or a host name on that one — with a warning, since the debugger is then reachable from the network.
     */
    @Parameter(property = "vidocq.run.debug.host", defaultValue = JdwpAgent.DEFAULT_HOST)
    private String debugHost;

    /** Suspend the child JVM until a debugger attaches ({@code suspend=y}), to debug boot itself. */
    @Parameter(property = "vidocq.run.debug.suspend", defaultValue = "false")
    private boolean debugSuspend;

    /**
     * Grace period given to the child after {@link Process#destroy()} — on Ctrl+C, or when the build is
     * interrupted — before it is force-killed.
     */
    @Parameter(property = "vidocq.run.gracePeriodMillis", defaultValue = "5000")
    private long gracePeriodMillis;

    /** Skip the goal entirely. */
    @Parameter(property = "vidocq.run.skip", defaultValue = "false")
    private boolean skip;

    /**
     * Provision dev-mode services (Postgres, Keycloak, …), the way {@code vidocq:dev} does — off by
     * default, since {@code vidocq:run} is also how the application runs in CI and in scripts, where
     * nothing should reach out for a container. A {@code vidocq.dev.devServices=true} entry in the
     * application's own files (spec §6) turns it on the same way the field does; see {@link
     * #devServicesEnabled}.
     */
    @Parameter(property = "vidocq.dev.devServices", defaultValue = "false")
    private boolean devServices;

    @Parameter(defaultValue = "${project.build.outputDirectory}", readonly = true)
    private File classesDir;

    @Parameter(defaultValue = "${project.basedir}", readonly = true)
    private File baseDir;

    @Parameter(defaultValue = "${project.build.directory}", readonly = true)
    private File buildDir;

    /** This plugin's own resolved dependencies, keyed {@code groupId:artifactId} — {@link
     *  DevServicesExtensionJar} looks the dev services extension jar up here to add it to the child's
     *  module path. */
    @Parameter(defaultValue = "${plugin.artifactMap}", readonly = true)
    private Map<String, Artifact> pluginArtifactMap;

    /**
     * The dev services session started by this run, if any — set in {@link #execute()}, closed exactly
     * once whether the application exits normally ({@code execute()}'s {@code finally}) or is stopped by
     * Ctrl+C (the shutdown hook inside {@link #await}): whichever runs first clears the reference, so the
     * other finds it already {@code null}.
     */
    private final AtomicReference<DevServicesSession> devServicesSession = new AtomicReference<>();

    @Override
    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info(PREFIX + "skipped (vidocq.run.skip=true)");
            return;
        }
        Path projectDir = baseDir.toPath();
        Path build = buildDirPath();
        Path classes = classesDir.toPath();
        // Universal-loader mode, as vidocq:dev and the production launcher use it: the application
        // classes stay off the module path and boot in a Vauban-defined module layer.
        List<Path> modulePath = ApplicationLaunch.modulePath(project, build, classes, true,
                jar -> getLog().info(PREFIX + "using the modularized copy of " + jar.getFileName()));
        List<Path> appPath = ApplicationLaunch.appPath(classes, true);
        Map<String, String> systemProperties = buildSystemProperties();
        List<String> jvmArgs = buildJvmArgs();

        try {
            List<String> patchArgs = ApplicationLaunch.patchModuleArgs(project, build);
            if (!patchArgs.isEmpty()) {
                jvmArgs.addAll(patchArgs);
                getLog().info(PREFIX + "JPMS: " + patchArgs.size() / 2
                        + " --patch-module option(s) added for generated classes");
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to compute --patch-module options", e);
        }

        getLog().info(PREFIX + "main module : " + mainModule
                + (mainClass != null && !mainClass.isBlank() ? ("/" + mainClass) : ""));

        DevServicesSession devs = null;
        if (devServicesEnabled(systemProperties, ApplicationFiles.of(classes))) {
            // Resolved before open(): a missing extension/devconsole-spi jar must abort before any
            // container is started, never leave a running session with nothing left to close it.
            List<Path> extensionJars = DevServicesExtensionJar.resolve(pluginArtifactMap, project.getArtifacts());
            try {
                devs = DevServicesSession.open("vidocq:run", projectDir, systemProperties, ApplicationFiles.of(classes),
                        System.getLogger("vidocq.run.devservices"));
            } catch (DevServicesException e) {
                throw new MojoExecutionException(e.getMessage(), e);
            }
            devs.injected().forEach(systemProperties::putIfAbsent);
            devs.providers().forEach((k, id) -> systemProperties.putIfAbsent("vidocq.dev.provided." + k, id));
            systemProperties.putIfAbsent(StateFile.PROPERTY, devs.stateFile().toAbsolutePath().toString());
            modulePath.addAll(extensionJars);
        }
        devServicesSession.set(devs);

        getLog().info(PREFIX + "module path entries: " + modulePath.size());
        logDebugAgent();

        try {
            await(ChildJvm.of(modulePath, appPath, mainModule, mainClass, jvmArgs, systemProperties,
                    projectDir, splitArgs(appArgs)));
        } finally {
            closeDevServices();
        }
    }

    /**
     * {@code vidocq.dev.devServices} (the field) or a {@code vidocq.dev.devServices=true} entry in the
     * application's own files (spec §6) — either one is enough, since a project may prefer to always run
     * with dev services rather than pass the flag on every {@code vidocq:run} invocation.
     */
    // package-private for the unit test.
    boolean devServicesEnabled(Map<String, String> sysProps, Function<String, Optional<String>> files) {
        return devServices || "true".equals(files.apply("vidocq.dev.devServices").orElse(null));
    }

    /**
     * Closes the dev services session exactly once, and only returns to <em>either</em> caller after the
     * close has actually finished. The shutdown hook (Ctrl+C) and this {@code finally} block both call this
     * — {@code synchronized} so that whichever runs first performs the close while holding the lock, and the
     * other blocks on the same lock until it is released, instead of racing {@link
     * #devServicesSession}'s {@code getAndSet(null)} and returning immediately with the containers still
     * being torn down. A racing loser that merely saw {@code null} would let the JVM believe shutdown is
     * complete — and halt — while {@link DevServicesSession#close()} is still stopping containers on the
     * other thread (Testcontainers/Ryuk left to clean up, and the state file stuck at {@code "running"}).
     */
    // package-private for the unit test of the close-exactly-once wiring.
    synchronized void closeDevServices() {
        DevServicesSession session = devServicesSession.getAndSet(null);
        if (session != null) {
            session.close();
        }
    }

    /**
     * Starts the child, waits for it, and turns its exit code into the goal's result: zero passes, anything
     * else fails the build. Ctrl+C is not a failure — the shutdown hook stopped the application on purpose.
     */
    void await(ChildJvm child) throws MojoExecutionException {
        AtomicReference<ChildJvm> running = new AtomicReference<>();
        // Ctrl+C reaches Maven, not only the child: the hook stops the application the way a SIGTERM
        // would, so its own shutdown hooks drain before the JVM is force-killed.
        AtomicBoolean stopped = new AtomicBoolean();
        Thread hook = new Thread(() -> {
            stopped.set(true);
            stop(running.getAndSet(null));
            // Covers a JVM that exits (Ctrl+C) before execute()'s finally runs. closeDevServices() is
            // synchronized: if execute()'s finally is already closing the session on the main thread, this
            // call blocks until that close has fully finished before the hook (and the shutdown sequence)
            // can proceed.
            closeDevServices();
        }, "vidocq-run-shutdown");
        Runtime.getRuntime().addShutdownHook(hook);

        int exitCode;
        try {
            long pid = child.start();
            running.set(child);
            getLog().info(PREFIX + "started, pid=" + pid + " (Ctrl+C to stop).");
            exitCode = child.waitFor();
        } catch (IOException e) {
            throw new MojoExecutionException("Cannot start the application JVM", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stop(running.getAndSet(null));
            getLog().info(PREFIX + "interrupted — the application was stopped.");
            return;
        } finally {
            running.set(null);
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException shuttingDown) {
                // The hook is running: it stops the child itself.
            }
        }

        if (exitCode != 0 && !stopped.get()) {
            throw new MojoExecutionException(PREFIX + "the application exited with code " + exitCode
                    + ". Its own output, above, says why; the build fails because a non-zero exit code is a"
                    + " failed run.");
        }
        getLog().info(PREFIX + "the application exited with code " + exitCode + ".");
    }

    private void stop(ChildJvm child) {
        if (child == null) {
            return;
        }
        try {
            child.stop(Duration.ofMillis(gracePeriodMillis));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * {@code ${project.build.directory}}, falling back to the parent of the (always injected) classes
     * directory ({@code target/classes} → {@code target}) when Maven did not inject it.
     */
    private Path buildDirPath() {
        return (buildDir != null ? buildDir : classesDir.getParentFile()).toPath();
    }

    /**
     * The child's system properties: what {@code vidocq.run.systemProperties} declares, then every
     * {@code -Dvidocq.*} of the Maven command line that is not a build setting, then the colour policy.
     */
    private Map<String, String> buildSystemProperties() {
        Map<String, String> properties = new LinkedHashMap<>();
        if (extraSystemProperties != null && !extraSystemProperties.isBlank()) {
            for (String pair : extraSystemProperties.split(",")) {
                int equals = pair.indexOf('=');
                if (equals > 0) {
                    properties.put(pair.substring(0, equals).strip(), pair.substring(equals + 1).strip());
                }
            }
        }
        forwarded(session == null ? new Properties() : session.getUserProperties()).forEach(properties::putIfAbsent);
        ConsoleColors.forChild(properties.containsKey(ConsoleColors.COLOR_KEY)).ifPresent(choice -> {
            properties.put(ConsoleColors.COLOR_KEY, choice.mode());
            getLog().debug(PREFIX + choice.logLine());
        });
        return properties;
    }

    /**
     * The {@code -Dvidocq.*} properties of the Maven command line that configure the application, not the
     * build: the child is another JVM, so they would not reach it otherwise. {@code vidocq:dev} forwards the
     * same set, so a key behaves the same whichever goal starts the application.
     */
    public static Map<String, String> forwarded(Properties userProperties) {
        Map<String, String> forwarded = new LinkedHashMap<>();
        for (String key : userProperties.stringPropertyNames()) {
            if (!key.startsWith("vidocq.") || BUILD_KEYS.contains(key)
                    || BUILD_PREFIXES.stream().anyMatch(key::startsWith)) {
                continue;
            }
            forwarded.put(key, userProperties.getProperty(key));
        }
        return forwarded;
    }

    /** The verbatim {@code vidocq.run.jvmArgs}, then the JDWP agent when {@link #debug} is on. */
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
        getLog().info(PREFIX + "debug agent (JDWP) on " + agent.where()
                + (debugSuspend ? " — the JVM suspends until a debugger attaches" : " — attach any time"));
        agent.exposure("vidocq.run.debug.host").ifPresent(warning -> getLog().warn(PREFIX + warning));
    }

    private static List<String> splitArgs(String raw) {
        if (raw == null || raw.isBlank()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(Arrays.asList(raw.strip().split("\\s+")));
    }

    // Package-private accessors used in unit tests — keep at the bottom so the execute() flow is the
    // first thing a reader sees.
    void setProject(MavenProject project) { this.project = project; }
    void setSession(MavenSession session) { this.session = session; }
    void setMainModule(String mainModule) { this.mainModule = mainModule; }
    void setMainClass(String mainClass) { this.mainClass = mainClass; }
    void setBaseDir(File baseDir) { this.baseDir = baseDir; }
    void setBuildDir(File buildDir) { this.buildDir = buildDir; }
    void setClassesDir(File classesDir) { this.classesDir = classesDir; }
    void setDevServices(boolean devServices) { this.devServices = devServices; }
    void setPluginArtifactMap(Map<String, Artifact> pluginArtifactMap) { this.pluginArtifactMap = pluginArtifactMap; }
    void setDevServicesSession(DevServicesSession session) { this.devServicesSession.set(session); }
    void setExtraJvmArgs(String extraJvmArgs) { this.extraJvmArgs = extraJvmArgs; }
    void setAppArgs(String appArgs) { this.appArgs = appArgs; }
    void setExtraSystemProperties(String extraSystemProperties) { this.extraSystemProperties = extraSystemProperties; }
    void setSkip(boolean skip) { this.skip = skip; }
    void setGracePeriodMillis(long gracePeriodMillis) { this.gracePeriodMillis = gracePeriodMillis; }
    void setDebugOptions(boolean debug, int port, boolean suspend) {
        this.debug = debug;
        this.debugPort = port;
        this.debugSuspend = suspend;
    }
    void setDebugHost(String debugHost) { this.debugHost = debugHost; }
    List<String> debugJvmArgs() { return buildJvmArgs(); }
    Map<String, String> debugSystemProperties() { return buildSystemProperties(); }
    List<String> debugAppArgs() { return splitArgs(appArgs); }
}
