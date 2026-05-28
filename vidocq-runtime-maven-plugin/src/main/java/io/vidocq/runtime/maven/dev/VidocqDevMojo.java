package io.vidocq.runtime.maven.dev;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

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
 *   <li>On a relevant change (debounced 250 ms by default), shell out
 *       {@code mvn process-classes}; on success, gracefully stop the child JVM
 *       and respawn it.</li>
 * </ol>
 *
 * <p>The Mojo is <b>blocking</b> — it keeps the terminal until the user hits
 * Ctrl+C, at which point a JVM shutdown hook kills the child cleanly. No state
 * survives a reload: every cycle is a "production miniature", which is why
 * Approach A honours JPMS strict + static codegen with zero risk of class-loader
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

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /**
     * Java module containing the main class. Required — Vidocq apps are
     * always launched on the module path, never the class path.
     */
    @Parameter(property = "vidocq.mainModule", required = true)
    private String mainModule;

    /**
     * Fully-qualified main class. Optional: when omitted, the child JVM relies
     * on the {@code ModuleMainClass} attribute baked into the app's
     * {@code module-info.class}.
     */
    @Parameter(property = "vidocq.mainClass")
    private String mainClass;

    /** Directories to watch, relative to the project base dir. */
    @Parameter(property = "vidocq.dev.watchDirs",
            defaultValue = "src/main/java,src/main/resources")
    private String watchDirs;

    /** Debounce window in milliseconds — collapses bursts of editor saves. */
    @Parameter(property = "vidocq.dev.debounceMillis", defaultValue = "250")
    private long debounceMillis;

    /**
     * Grace period given to the child JVM after {@link Process#destroy()}
     * before {@link Process#destroyForcibly()} fires. Five seconds is plenty
     * for the chappe drain in dev — production keeps the 30 s default.
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

    @Parameter(defaultValue = "${project.build.outputDirectory}", readonly = true)
    private File classesDir;

    @Parameter(defaultValue = "${project.basedir}", readonly = true)
    private File baseDir;

    @Override
    public void execute() throws MojoExecutionException {
        Path projectDir = baseDir.toPath();
        List<Path> watch = parseWatchDirs(projectDir);
        List<Path> modulePath = buildModulePath();
        Map<String, String> sysProps = buildSystemProperties();
        List<String> jvmArgs = splitArgs(extraJvmArgs);

        getLog().info("Vidocq dev — main module : " + mainModule
                + (mainClass != null && !mainClass.isBlank() ? ("/" + mainClass) : ""));
        getLog().info("Watching: " + watch);
        getLog().info("Module path entries: " + modulePath.size());

        // The atomic reference lets the shutdown hook (running on a separate
        // thread) see the latest spawned child, no matter how many reload
        // cycles we have been through.
        AtomicReference<ChildJvm> currentChild = new AtomicReference<>();
        Thread mainThread = Thread.currentThread();

        Thread hook = new Thread(() -> {
            mainThread.interrupt();
            ChildJvm c = currentChild.get();
            if (c != null) {
                try {
                    c.stop(Duration.ofMillis(gracePeriodMillis));
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "vidocq-dev-shutdown");
        Runtime.getRuntime().addShutdownHook(hook);

        RecompileRunner recompile = new RecompileRunner(projectDir);

        try (SourceWatcher watcher = SourceWatcher.on(watch, Duration.ofMillis(debounceMillis))) {
            ChildJvm child = ChildJvm.of(modulePath, mainModule, mainClass,
                    jvmArgs, sysProps, projectDir);
            long pid = child.start();
            currentChild.set(child);
            getLog().info("Child JVM started, pid=" + pid + ". Listening for changes (Ctrl+C to stop).");

            while (!Thread.currentThread().isInterrupted()) {
                if (!watcher.awaitChange()) {
                    break; // watcher closed
                }
                long t0 = System.nanoTime();
                getLog().info("Changes detected — recompiling...");
                int rc;
                try {
                    rc = recompile.run();
                } catch (IOException e) {
                    getLog().warn("Recompile invocation failed: " + e.getMessage());
                    continue;
                }
                if (rc != 0) {
                    getLog().warn("Compile failed (exit " + rc + "); keeping previous JVM up.");
                    continue;
                }
                child.stop(Duration.ofMillis(gracePeriodMillis));
                child = ChildJvm.of(modulePath, mainModule, mainClass,
                        jvmArgs, sysProps, projectDir);
                pid = child.start();
                currentChild.set(child);
                long elapsed = (System.nanoTime() - t0) / 1_000_000;
                getLog().info("Reloaded in " + elapsed + " ms (pid=" + pid + ").");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            getLog().info("Dev mode interrupted — shutting down.");
        } catch (IOException e) {
            throw new MojoExecutionException("Dev mode initialisation failed", e);
        } finally {
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
            // Avoid IllegalStateException if the JVM is mid-shutdown.
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException ignored) {
                // shutdown already in progress
            }
        }
    }

    private List<Path> parseWatchDirs(Path projectDir) {
        List<Path> result = new ArrayList<>();
        for (String s : watchDirs.split(",")) {
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
        List<Path> entries = new ArrayList<>();
        entries.add(classesDir.toPath());
        for (var artifact : project.getArtifacts()) {
            if (artifact.getFile() != null && "jar".equals(artifact.getType())) {
                entries.add(artifact.getFile().toPath());
            }
        }
        return entries;
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
        return props;
    }

    private static List<String> splitArgs(String raw) {
        if (raw == null || raw.isBlank()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(Arrays.asList(raw.trim().split("\\s+")));
    }

    // Package-private accessors used in unit tests — keep at the bottom so the
    // execute() flow is the first thing a reader sees.
    void setProject(MavenProject project) { this.project = project; }
    void setMainModule(String mainModule) { this.mainModule = mainModule; }
    void setMainClass(String mainClass) { this.mainClass = mainClass; }
    void setBaseDir(File baseDir) { this.baseDir = baseDir; }
    void setClassesDir(File classesDir) { this.classesDir = classesDir; }
    void setExtraSystemProperties(String s) { this.extraSystemProperties = s; }
    void setProfile(String profile) { this.profile = profile; }
    Map<String, String> debugSystemProperties() { return buildSystemProperties(); }
}
