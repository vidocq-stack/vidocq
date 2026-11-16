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
package io.vidocq.runtime.it.applayer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * vidocq:jlink: the image links every module, and its start has one layer, the JVM's own: no application layer, so
 * no second copy of the application module (BUG-20261114-11, Vidocq/vidocq#580). Both launch shapes: the fixture's
 * trampoline main, and the runtime's own main when the build names no main class.
 */
class AppLayerImageIT {

    private static final String STARTED = "Vidocq - Started in";
    private static final String LAUNCHER = "app/target/dist/bin/app-layer-app";
    private static final String PLACED_LAUNCHER = "placed/target/dist/bin/app-layer-placed";

    @Test
    void theImageOfATrampolineLoadsTheApplicationOnce(@TempDir Path dir) throws Exception {
        String out = buildAndStart(dir, "image-main");
        assertLoadedOnceFromTheImage(out);
    }

    @Test
    void theImageWithoutAMainClassLoadsTheApplicationOnce(@TempDir Path dir) throws Exception {
        String out = buildAndStart(dir, "image-no-main", "-Dvidocq.mainClass=io.vidocq.runtime.core.Vidocq");
        assertLoadedOnceFromTheImage(out);
    }

    /** The application and its two libraries are modules of the image, loaded by the JVM's application loader. */
    private static void assertLoadedOnceFromTheImage(String out) {
        assertTrue(out.contains(AppLayerPackageIT.ONE_COPY), "one copy of the application module:\n" + out);
        assertTrue(out.contains("no application layer"), "the image has one layer:\n" + out);
        assertTrue(out.contains("LOOKUP-LOADER app"), "the library is loaded by the JVM's loader:\n" + out);
        // A library finds the application's resource, which no package holds, in the image (Foy#96).
        assertTrue(out.contains("LOOKUP-FOUND jrt:/"), "the library sees the application's resource:\n" + out);
        // An image has no directory entry: a library lists a directory of the application from its modules
        // (BUG-20261115-02, Flyway found no migration).
        assertTrue(out.contains("LIST-HOME [META-INF/acme/index.html]"), "a directory of the application:\n" + out);
        assertTrue(out.contains("SQL-OK"), "a JDK module only a library requires is resolved (vidocq#254):\n" + out);
        assertTrue(out.contains("anomalies   none"), out);
        assertFalse(out.contains(AppLayerPackageIT.TWICE_WARNING), out);
    }

    /**
     * The options the image bakes leave the JVM's own tools working: a flight recording, {@code jcmd}, and the AOT
     * cache (CDS), which the JVM turns off under some module options ({@code --limit-modules}). A flight recording
     * needs {@code jdk.jfr} in the image, which the build adds on request.
     */
    @Test
    void theImageKeepsJfrJcmdAndTheAotCache(@TempDir Path dir) throws Exception {
        Path launcher = build(dir, "image-tools", "-Dvidocq.jlink.addModules=jdk.jfr");

        Path recording = dir.resolve("app.jfr");
        Process run = start(launcher, "image-tools-jfr", "-XX:StartFlightRecording=filename=" + recording);
        try {
            ProcessHandle jvm = jvmOf(run);
            Process jcmd = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "jcmd").toString(),
                    Long.toString(jvm.pid()), "VM.version").redirectErrorStream(true).start();
            String version = new String(jcmd.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(jcmd.waitFor(1, TimeUnit.MINUTES) && jcmd.exitValue() == 0, version);
            assertTrue(version.contains("JDK " + Runtime.version().feature()), version);
        } finally {
            stopAndWait(run);
        }
        assertTrue(Files.size(recording) > 0, "no flight recording");

        Path cache = dir.resolve("app.aot");
        stopAndWait(start(launcher, "image-tools-aot-record", "-XX:AOTCacheOutput=" + cache));
        assertTrue(Files.isRegularFile(cache), "no AOT cache:\n" + Fixture.read(Fixture.logs()
                .resolve("image-tools-aot-record-run.log")));
        Process cached = start(launcher, "image-tools-aot", "-XX:AOTCache=" + cache, "-Xlog:aot=info");
        stopAndWait(cached);
        String out = Fixture.read(Fixture.logs().resolve("image-tools-aot-run.log"));
        assertTrue(out.contains("Opened AOT cache"), "the AOT cache was not used:\n" + out);
        assertTrue(out.contains(AppLayerPackageIT.ONE_COPY), out);
    }

    /**
     * A producer of a third-party class in a normal scope, when that class has a package-private member: its client
     * proxy must be a class of the library's own package. An image has no application layer to define it there at
     * each start, so the build puts it in the copy of the library it links (BUG-20261116-06, Vidocq/vidocq#580).
     * One documented difference with the application layer: the proxy of the image runs a constructor of the
     * library's class, which the layer's proxy does not.
     */
    @Test
    void theImageHoldsTheProxyOfAThirdPartyClassInItsPackage(@TempDir Path dir) throws Exception {
        Path launcher = buildImage(dir, "placed", PLACED_LAUNCHER, "image-placed");
        Process run = launch(launcher, "image-placed");
        try {
            Path runLog = Fixture.logs().resolve("image-placed-run.log");
            Fixture.awaitLog(run, runLog, AppLayerRunIT.PLACED_PROXY);
            String out = Fixture.read(runLog);
            AppLayerRunIT.assertPlacedProxy(out);
            assertTrue(out.contains("GADGET-CONSTRUCTED 2"), "the proxy runs a constructor of the library:\n" + out);
            assertTrue(out.contains("no application layer"), "the image has one layer:\n" + out);
            assertTrue(out.contains("anomalies   none"), out);
        } finally {
            Fixture.stop(run);
        }
    }

    private static String buildAndStart(Path dir, String name, String... options) throws Exception {
        Process run = start(build(dir, name, options), name);
        try {
            return Fixture.read(Fixture.logs().resolve(name + "-run.log"));
        } finally {
            Fixture.stop(run);
        }
    }

    /** Builds the image of the fixture's main application; returns its launcher. */
    private static Path build(Path dir, String name, String... options) throws Exception {
        return buildImage(dir, "app", LAUNCHER, name, options);
    }

    /** Builds the image of the application in {@code module} of the fixture; returns its launcher. */
    private static Path buildImage(Path dir, String module, String launcherPath, String name, String... options)
            throws Exception {
        Path project = Fixture.copy(dir);
        Fixture.install(project, Fixture.logs().resolve(name + "-install.log"));
        Path log = Fixture.logs().resolve(name + ".log");
        String[] args = new String[options.length + 3];
        args[0] = "package";
        args[1] = "vidocq:jlink";
        args[2] = "-DskipTests";
        System.arraycopy(options, 0, args, 3, options.length);
        Fixture.awaitSuccess(Fixture.start(project.resolve(module), log, args), log, "vidocq:jlink");
        Path launcher = project.resolve(launcherPath);
        assertTrue(Files.isRegularFile(launcher), "vidocq:jlink wrote no launcher:\n" + Fixture.read(log));
        return launcher;
    }

    /** Starts the image of the main application, and waits until it started and probed its layer. */
    private static Process start(Path launcher, String name, String... jvmOptions) throws Exception {
        Process run = launch(launcher, name, jvmOptions);
        Fixture.awaitLog(run, Fixture.logs().resolve(name + "-run.log"), "TOOL-PROVIDERS ");
        return run;
    }

    /**
     * Starts the image's launcher, the JVM options read by the java launcher itself (the launcher of a jlink image
     * passes no {@code JAVA_OPTS}), and waits until the application started.
     */
    private static Process launch(Path launcher, String name, String... jvmOptions) throws Exception {
        Path runLog = Fixture.logs().resolve(name + "-run.log");
        ProcessBuilder command = new ProcessBuilder(launcher.toString()).redirectErrorStream(true)
                .redirectOutput(runLog.toFile());
        command.environment().put("JDK_JAVA_OPTIONS", String.join(" ", jvmOptions));
        Process run = command.start();
        Fixture.awaitLog(run, runLog, STARTED);
        return run;
    }

    /** The JVM of a run: the launcher of a jlink image starts java as its child. */
    private static ProcessHandle jvmOf(Process run) {
        return run.descendants().findFirst().orElse(run.toHandle());
    }

    /** Stops a run and waits for its JVM to end, which writes the recording or the AOT cache on its way out. */
    private static void stopAndWait(Process run) throws Exception {
        ProcessHandle jvm = jvmOf(run);
        jvm.destroy();
        jvm.onExit().get(Fixture.TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        Fixture.stop(run);
    }
}
