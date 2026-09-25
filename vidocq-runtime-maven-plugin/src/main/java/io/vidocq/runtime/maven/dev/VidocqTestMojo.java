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
import io.vidocq.runtime.maven.dev.TestResults.Trigger;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * {@code vidocq:test} (#122, spec §4): the application's tests on every change, without the application. No child
 * JVM, no dev console, no debug agent: the goal opens the dev services session once, runs every test, then watches
 * the main and test directories and runs them again on each change, a new change cancelling the run in flight. A
 * main change needs no separate recompile: the run's {@code test-compile} compiles and indexes the main sources.
 *
 * <p>Each run prints a summary; with a console, {@code r} runs every test, {@code f} the failed ones and {@code q}
 * quits. Ctrl+C always stops the goal, its run and its dev services.
 */
@Mojo(name = "test", defaultPhase = LifecyclePhase.NONE, threadSafe = false)
public class VidocqTestMojo extends AbstractMojo {

    /** The dev services host this goal is. */
    static final String HOST = "vidocq:test";

    /** Main directories to watch, relative to the project base dir. */
    @Parameter(property = "vidocq.dev.watchDirs", defaultValue = "src/main/java,src/main/resources")
    private String watchDirs;

    /** Test directories to watch, relative to the project base dir. */
    @Parameter(property = "vidocq.dev.testWatchDirs", defaultValue = "src/test/java,src/test/resources")
    private String testWatchDirs;

    /** Debounce window in milliseconds — collapses bursts of editor saves. */
    @Parameter(property = "vidocq.dev.debounceMillis", defaultValue = "250")
    private long debounceMillis;

    /**
     * Provision dev-mode services for the tests, as {@code vidocq:dev} does. Unset by default: then
     * {@code vidocq.dev.devServices} in the application's files decides, else on.
     */
    @Parameter(property = "vidocq.dev.devServices")
    private Boolean devServices;

    @Parameter(defaultValue = "${project.build.outputDirectory}", readonly = true)
    private File classesDir;

    @Parameter(defaultValue = "${project.basedir}", readonly = true)
    private File baseDir;

    @Parameter(defaultValue = "${project.build.directory}", readonly = true)
    private File buildDir;

    @Override
    public void execute() throws MojoExecutionException {
        Path projectDir = baseDir.toPath();
        List<Path> mainDirs = VidocqDevMojo.parseDirs(projectDir, watchDirs);
        List<Path> testDirs = VidocqDevMojo.parseDirs(projectDir, testWatchDirs);
        Function<String, Optional<String>> files = ApplicationFiles.of(classesDir.toPath());
        DevServicesSession devs = null;
        if (devServicesEnabled(files)) {
            try {
                devs = DevServicesSession.open(HOST, projectDir, new LinkedHashMap<>(), files,
                        System.getLogger("vidocq.test.devservices"));
            } catch (DevServicesException e) {
                throw new MojoExecutionException(e.getMessage(), e);
            }
        }
        final DevServicesSession session = devs;
        Map<String, String> testProps = new LinkedHashMap<>();
        if (session != null) {
            session.foldInto(testProps);
        }
        boolean keys = TerminalControls.attached();
        ContinuousTesting testing = ContinuousTesting.forProject(projectDir, buildDir.toPath(), testProps,
                result -> TestSummaryPrinter.lines(result, true, keys).forEach(getLog()::info), getLog()::warn);

        Thread mainThread = Thread.currentThread();
        Thread hook = new Thread(() -> {
            mainThread.interrupt();
            testing.close();
            closeDevServices(session);
        }, "vidocq-test-shutdown");
        Runtime.getRuntime().addShutdownHook(hook);

        try (SourceWatcher watcher = SourceWatcher.on(mainDirs, testDirs, Duration.ofMillis(debounceMillis))) {
            getLog().info("Vidocq test — watching " + mainDirs + " and " + testDirs
                    + (keys ? "" : "; no console, so no keys: Ctrl+C to stop"));
            testing.start();
            testing.changed(Trigger.RUN_ALL, TestControl.ReadyGate.NOW);
            if (keys) {
                listenToKeys(testing, watcher);
            }
            while (!Thread.currentThread().isInterrupted()) {
                SourceWatcher.Change change = watcher.awaitChanges();
                if (change == null) {
                    break; // watcher closed: q, or the end
                }
                testing.changed(change.main() ? Trigger.CHANGE : Trigger.TEST_CHANGE, TestControl.ReadyGate.NOW);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            getLog().info("Vidocq test interrupted — shutting down.");
        } catch (IOException e) {
            throw new MojoExecutionException("Continuous testing initialisation failed", e);
        } finally {
            testing.close();
            closeDevServices(session);
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException ignored) {
                // shutdown already in progress
            }
        }
    }

    /** Reads the keys on the daemon thread {@code vidocq-test-keys}; {@code q} closes the watcher. */
    private void listenToKeys(ContinuousTesting testing, SourceWatcher watcher) {
        TerminalControls controls = new TerminalControls(System.in, command -> {
            switch (command) {
                case RUN_ALL -> testing.request(Trigger.RUN_ALL);
                case RERUN_FAILED -> {
                    String outcome = testing.request(Trigger.RERUN_FAILED);
                    if (!ContinuousTesting.QUEUED.equals(outcome)) {
                        getLog().info("Tests: " + outcome);
                    }
                }
                case QUIT -> {
                    try {
                        watcher.close();
                    } catch (IOException ignored) {
                        // the loop ends anyway
                    }
                }
            }
        });
        Thread.ofPlatform().name("vidocq-test-keys").daemon(true).start(controls);
    }

    /** {@code vidocq.dev.devServices}: the explicit value, then the application's files, then on. */
    // package-private for the unit test.
    boolean devServicesEnabled(Function<String, Optional<String>> files) throws MojoExecutionException {
        try {
            return DevServicesFlag.enabled(Optional.ofNullable(devServices).map(String::valueOf), files, true);
        } catch (IllegalArgumentException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }

    /** Closes {@code session} once, the shutdown hook and {@code finally} waiting for each other (#123). */
    synchronized void closeDevServices(DevServicesSession session) {
        if (session != null) {
            session.close();
        }
    }

    void setDevServices(Boolean devServices) {
        this.devServices = devServices;
    }
}
