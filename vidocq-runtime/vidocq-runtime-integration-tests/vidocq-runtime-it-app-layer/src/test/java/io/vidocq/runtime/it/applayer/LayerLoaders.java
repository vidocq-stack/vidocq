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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The application layers alive in the child JVM of {@code vidocq:dev}, counted from outside: each layer has one
 * loader, and {@code jcmd} counts the loaders a full collection leaves (Vidocq/vidocq#631).
 */
final class LayerLoaders {

    /** The class of the loader of an application layer. */
    static final String LOADER = "io.vidocq.vauban.classloader.VaubanClassLoader";

    /** How many collections {@link #settled} waits for: what a boot leaves behind goes within a few of them. */
    private static final int COLLECTIONS = 10;

    /** The histogram row of {@link #LOADER}: rank, instances, bytes, class name, then its module. */
    private static final Pattern ROW = Pattern.compile(
            "^\\s*\\d+:\\s+(\\d+)\\s+\\d+\\s+" + Pattern.quote(LOADER) + "(?:\\s.*)?$", Pattern.MULTILINE);

    /** The line {@code vidocq:dev} logs once its child JVM runs. */
    private static final Pattern CHILD = Pattern.compile("Child JVM started, pid=(\\d+)");

    private LayerLoaders() {}

    /** The pid of the child JVM of the {@code vidocq:dev} run that writes {@code log}. */
    static long childPid(Path log) {
        Matcher child = CHILD.matcher(Fixture.read(log));
        if (!child.find()) {
            fail("no pid of the child JVM in the log:\n" + Fixture.read(log));
        }
        return Long.parseLong(child.group(1));
    }

    /**
     * The layer loaders alive in the JVM {@code pid}. {@code GC.class_histogram} runs a full collection before it
     * counts, so a loader it reports is one that something still reaches.
     */
    static int live(long pid) throws Exception {
        Path histogram = Files.createTempFile("class-histogram", ".txt");
        try {
            Process jcmd = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "jcmd").toString(),
                    Long.toString(pid), "GC.class_histogram")
                    .redirectErrorStream(true)
                    .redirectOutput(histogram.toFile())
                    .start();
            if (!jcmd.waitFor(Fixture.TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                jcmd.destroyForcibly();
                fail("jcmd " + pid + " GC.class_histogram did not end within " + Fixture.TIMEOUT);
            }
            String printed = Fixture.read(histogram);
            assertEquals(0, jcmd.exitValue(), () -> "jcmd " + pid + " GC.class_histogram failed:\n" + printed);
            return count(printed);
        } finally {
            Files.deleteIfExists(histogram);
        }
    }

    /** The instances of {@link #LOADER} a class histogram reports: none when it has no row for the class. */
    static int count(String histogram) {
        Matcher row = ROW.matcher(histogram);
        return row.find() ? Integer.parseInt(row.group(1)) : 0;
    }

    /**
     * The count once it reads {@code expected}, or the last one read after {@link #COLLECTIONS} collections a second
     * apart: a stopped deployment is released a moment after the next one started.
     */
    static int settled(Process mvn, long pid, int expected) throws Exception {
        int live = live(pid);
        for (int collection = 1; live != expected && collection < COLLECTIONS; collection++) {
            if (mvn.waitFor(1, TimeUnit.SECONDS)) {
                fail("vidocq:dev exited with " + mvn.exitValue() + " while its layers were counted");
            }
            live = live(pid);
        }
        return live;
    }
}
