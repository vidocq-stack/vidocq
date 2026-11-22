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
package io.vidocq.runtime.devservices.junit;

import io.vidocq.runtime.devservices.host.DevServicesSession;
import io.vidocq.runtime.devservices.host.StateFile;
import io.vidocq.runtime.devservices.spi.DevService;
import io.vidocq.runtime.devservices.spi.DevServiceContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives {@link DevServicesSessionListener} directly, through its package-private constructor, with a fake
 * opener backed by {@link DevServicesSession#forTesting} (Task 4) rather than real {@code ServiceLoader}
 * discovery or a real {@code LauncherSession}. Both listener methods ignore their {@code LauncherSession}
 * argument, so tests pass {@code null} for it.
 */
class DevServicesSessionListenerTest {

    private static final System.Logger LOG = System.getLogger("test");
    /** The two paths of this test JVM, which two tests replace for a moment. */
    private static final String MODULE_PATH = System.getProperty("jdk.module.path");
    private static final String CLASS_PATH = System.getProperty("java.class.path");

    @AfterEach
    void restoreSystemProperties() {
        System.clearProperty("a.url");
        System.clearProperty("vidocq.dev.provided.a.url");
        System.clearProperty(StateFile.PROPERTY);
        System.clearProperty("vidocq.dev.devServices");
        System.clearProperty("basedir");
        restore("jdk.module.path", MODULE_PATH);
        restore("java.class.path", CLASS_PATH);
    }

    private static void restore(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }

    @Test
    void openInjectsPropertiesAndWritesTheStateFileThenCloseStopsTheProviderOnce(@TempDir Path basedir) {
        System.setProperty("basedir", basedir.toString());
        Fake fake = new Fake("a", false);
        AtomicInteger calls = new AtomicInteger();
        DevServicesSessionListener listener = new DevServicesSessionListener((dir, classes) -> {
            calls.incrementAndGet();
            try {
                return DevServicesSession.forTesting("test", dir, List.of(fake), LOG);
            } catch (Exception e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        });

        listener.launcherSessionOpened(null);

        assertEquals(1, calls.get());
        assertEquals("jdbc:x://h:1/a", System.getProperty("a.url"));
        assertEquals("a", System.getProperty("vidocq.dev.provided.a.url"), "a taken value is marked");
        String stateFile = System.getProperty(StateFile.PROPERTY);
        assertTrue(stateFile != null && Files.exists(Path.of(stateFile)), "state file exists: " + stateFile);

        listener.launcherSessionClosed(null);

        assertEquals(1, fake.stops, "stopped exactly once");
    }

    @Test
    void aSystemPropertyAlreadySetBeforeOpeningKeepsItsValue(@TempDir Path basedir) {
        System.setProperty("basedir", basedir.toString());
        System.setProperty("a.url", "already-set");
        Fake fake = new Fake("a", false);
        DevServicesSessionListener listener = new DevServicesSessionListener(
                (dir, classes) -> forTesting(dir, fake));

        listener.launcherSessionOpened(null);

        assertEquals("already-set", System.getProperty("a.url"));
        assertNull(System.getProperty("vidocq.dev.provided.a.url"), "a kept value is not marked");

        listener.launcherSessionClosed(null);
    }

    @Test
    void devServicesOffSkipsOpeningEntirely(@TempDir Path basedir) {
        System.setProperty("basedir", basedir.toString());
        System.setProperty("vidocq.dev.devServices", "false");
        AtomicInteger calls = new AtomicInteger();
        DevServicesSessionListener listener =
                new DevServicesSessionListener((dir, classes) -> {
                    calls.incrementAndGet();
                    return forTesting(dir, new Fake("a", false));
                });

        listener.launcherSessionOpened(null);

        assertEquals(0, calls.get(), "opener never called");

        // No session was opened, so closing must be a harmless no-op.
        listener.launcherSessionClosed(null);
    }

    @Test
    void aFalseInTheApplicationsFilesSkipsOpening(@TempDir Path basedir) throws Exception {
        System.setProperty("basedir", basedir.toString());
        writeVidocqProperties(basedir, "vidocq.dev.devServices = False\n");
        AtomicInteger calls = new AtomicInteger();
        DevServicesSessionListener listener = new DevServicesSessionListener((dir, classes) -> {
            calls.incrementAndGet();
            return forTesting(dir, new Fake("a", false));
        });

        listener.launcherSessionOpened(null);

        assertEquals(0, calls.get(), "the file's false is read, whatever its case");
        listener.launcherSessionClosed(null);
    }

    @Test
    void anExplicitSystemPropertyBeatsTheApplicationsFiles(@TempDir Path basedir) throws Exception {
        System.setProperty("basedir", basedir.toString());
        writeVidocqProperties(basedir, "vidocq.dev.devServices=false\n");
        System.setProperty("vidocq.dev.devServices", " TRUE ");
        AtomicInteger calls = new AtomicInteger();
        DevServicesSessionListener listener = new DevServicesSessionListener((dir, classes) -> {
            calls.incrementAndGet();
            return forTesting(dir, new Fake("a", false));
        });

        listener.launcherSessionOpened(null);

        assertEquals(1, calls.get(), "the explicit true wins over the file's false");
        listener.launcherSessionClosed(null);
    }

    /**
     * BUG-20261122-03: a test started from an IDE that compiles into {@code target-ide/} runs on those classes; the
     * {@code vidocq.properties} Maven left in {@code target/classes} is another build's.
     */
    @Test
    void theApplicationsFilesAreReadWhereTheTestJvmRuns(@TempDir Path basedir) throws Exception {
        System.setProperty("basedir", basedir.toString());
        writeVidocqProperties(basedir, "vidocq.dev.devServices=true\n");
        Path ides = Files.createDirectories(basedir.resolve("target-ide").resolve("classes"));
        Files.writeString(ides.resolve("vidocq.properties"), "vidocq.dev.devServices=false\n");
        System.setProperty("jdk.module.path", ides + File.pathSeparator + basedir.resolve("lib.jar"));
        AtomicInteger calls = new AtomicInteger();
        DevServicesSessionListener listener = new DevServicesSessionListener((dir, classes) -> {
            calls.incrementAndGet();
            return forTesting(dir, new Fake("a", false));
        });

        listener.launcherSessionOpened(null);

        assertEquals(0, calls.get(), "the false of the classes the JVM runs on is read, not Maven's true");
        listener.launcherSessionClosed(null);
    }

    /** The session reads the application's files from the directory the flag was read from. */
    @Test
    void theSessionIsOpenedOnTheSameClasses(@TempDir Path basedir) throws Exception {
        System.setProperty("basedir", basedir.toString());
        Path ides = Files.createDirectories(basedir.resolve("target-ide").resolve("classes"));
        System.clearProperty("jdk.module.path");
        System.setProperty("java.class.path", basedir.resolve("target-ide").resolve("test-classes")
                + File.pathSeparator + ides);
        List<Path> opened = new ArrayList<>();
        DevServicesSessionListener listener = new DevServicesSessionListener((dir, classes) -> {
            opened.add(classes);
            return forTesting(dir, new Fake("a", false));
        });

        listener.launcherSessionOpened(null);

        assertEquals(List.of(ides.toRealPath()), opened.stream().map(DevServicesSessionListenerTest::real).toList());
        listener.launcherSessionClosed(null);
    }

    @Test
    void theApplicationsClassesAreTheFirstClassesFolderOfTheModulePathUnderTheBaseDirectory(@TempDir Path dir)
            throws Exception {
        Path basedir = Files.createDirectories(dir.resolve("todo-app"));
        Path ides = Files.createDirectories(basedir.resolve("target-ide").resolve("classes"));
        Path library = Files.createDirectories(dir.resolve("todo-lib").resolve("target-ide").resolve("classes"));
        Path jar = Files.writeString(dir.resolve("core.jar"), "");
        Files.createDirectories(basedir.resolve("target").resolve("classes"));

        Path classes = DevServicesSessionListener.applicationClasses(basedir, path(jar, library, ides),
                path(basedir.resolve("target").resolve("classes")));

        assertEquals(ides.toRealPath(), real(classes));
    }

    /** No module path, or none of its entries: the class path, where a test of an application without modules runs. */
    @Test
    void thenOfTheClassPath(@TempDir Path basedir) throws Exception {
        Path ides = Files.createDirectories(basedir.resolve("target-ide").resolve("classes"));
        Path tests = Files.createDirectories(basedir.resolve("target-ide").resolve("test-classes"));
        Path jar = Files.writeString(basedir.resolve("core.jar"), "");

        assertEquals(ides.toRealPath(),
                real(DevServicesSessionListener.applicationClasses(basedir, null, path(tests, ides, jar))));
        assertEquals(ides.toRealPath(),
                real(DevServicesSessionListener.applicationClasses(basedir, path(jar), path(tests, ides, jar))));
    }

    /** What Maven's own run gives: the module path of Surefire names {@code target/classes}. */
    @Test
    void underMavenTheyAreMavens(@TempDir Path basedir) throws Exception {
        Path mavens = Files.createDirectories(basedir.resolve("target").resolve("classes"));
        Files.createDirectories(basedir.resolve("target-ide").resolve("classes"));

        assertEquals(mavens.toRealPath(),
                real(DevServicesSessionListener.applicationClasses(basedir, path(mavens), "surefirebooter.jar")));
    }

    /**
     * The folder of another module, a folder of another name, an entry that is no directory, an entry that is no
     * path: none is the application's. Without one, Maven's folder as before: Surefire's class path mode hides its
     * class path behind a jar that holds a manifest only.
     */
    @Test
    void withoutSuchAnEntryTheyAreMavens(@TempDir Path dir) throws Exception {
        Path basedir = Files.createDirectories(dir.resolve("todo-app"));
        Path library = Files.createDirectories(dir.resolve("todo-lib").resolve("target").resolve("classes"));
        Path tests = Files.createDirectories(basedir.resolve("target-ide").resolve("test-classes"));
        Path file = Files.writeString(Files.createDirectories(basedir.resolve("packed")).resolve("classes"), "");
        Path missing = basedir.resolve("target-ide").resolve("classes");
        Path mavens = basedir.resolve("target").resolve("classes");

        assertEquals(mavens, DevServicesSessionListener.applicationClasses(basedir, null, null));
        assertEquals(mavens, DevServicesSessionListener.applicationClasses(basedir, "", ""));
        assertEquals(mavens, DevServicesSessionListener.applicationClasses(basedir,
                path(library, tests, file, missing), path(library) + File.pathSeparator + File.pathSeparator));
        assertEquals(mavens, DevServicesSessionListener.applicationClasses(basedir, "\0", "surefirebooter.jar"));
        assertEquals(mavens, DevServicesSessionListener.applicationClasses(basedir, dir.getRoot().toString(), null));
    }

    /** A base directory that is not there has no classes but Maven's to name. */
    @Test
    void aBaseDirectoryThatDoesNotExistAnswersMavensFolder(@TempDir Path dir) throws Exception {
        Path basedir = dir.resolve("gone");
        Path elsewhere = Files.createDirectories(dir.resolve("todo").resolve("target-ide").resolve("classes"));

        assertEquals(basedir.resolve("target").resolve("classes"),
                DevServicesSessionListener.applicationClasses(basedir, path(elsewhere), null));
    }

    /** The JVM keeps a path as it was given: an entry is read from the working directory, as the JVM read it. */
    @Test
    void aRelativeEntryIsReadFromTheWorkingDirectory(@TempDir Path basedir) throws Exception {
        Path ides = Files.createDirectories(basedir.resolve("target-ide").resolve("classes")).toRealPath();
        Path relative = Path.of("").toRealPath().relativize(ides);

        assertEquals(ides, real(DevServicesSessionListener.applicationClasses(basedir, relative.toString(), null)));
    }

    private static String path(Path... entries) {
        return String.join(File.pathSeparator, Arrays.stream(entries).map(Path::toString).toList());
    }

    /** {@code path} with its links followed, as the temporary folder of macOS needs; as it is when it is missing. */
    private static Path real(Path path) {
        try {
            return Files.exists(path) ? path.toRealPath() : path.toAbsolutePath().normalize();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    /**
     * Review Focus: the test JVM's class path is asked through the context class loader, the driver a test
     * dependency; without one, the listener's own class loader answers.
     */
    @Test
    void theDevServicesSeeTheTestClassPathThroughTheContextClassLoader(@TempDir Path dir) throws Exception {
        Path driver = dir.resolve("org/postgresql/Driver.class");
        Files.createDirectories(driver.getParent());
        Files.write(driver, new byte[0]);

        try (URLClassLoader loader =
                new URLClassLoader(new URL[] {dir.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            assertTrue(DevServicesSessionListener.onTestClasspath(loader).test("org.postgresql.Driver"));
            assertFalse(DevServicesSessionListener.onTestClasspath(loader).test("com.mysql.cj.jdbc.Driver"));
        }
        assertFalse(DevServicesSessionListener.onTestClasspath(ClassLoader.getPlatformClassLoader())
                .test("org.postgresql.Driver"));
        assertTrue(DevServicesSessionListener.onTestClasspath(null)
                .test("org.junit.platform.launcher.LauncherSessionListener"), "no context loader: the listener's own");
    }

    private static void writeVidocqProperties(Path basedir, String content) throws Exception {
        Path classes = basedir.resolve("target").resolve("classes");
        Files.createDirectories(classes);
        Files.writeString(classes.resolve("vidocq.properties"), content);
    }

    private static DevServicesSession forTesting(Path dir, Fake fake) {
        try {
            return DevServicesSession.forTesting("test", dir, List.of(fake), LOG);
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    /** A minimal {@link DevService} scripted to succeed, recording how many times it was stopped. */
    private static final class Fake implements DevService {
        final String id;
        final boolean fail;
        int stops;

        Fake(String id, boolean fail) {
            this.id = id;
            this.fail = fail;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public boolean appliesWhen(DevServiceContext ctx) {
            return true;
        }

        @Override
        public Map<String, String> start(DevServiceContext ctx) throws Exception {
            if (fail) {
                throw new IllegalStateException("no docker");
            }
            return Map.of(id + ".url", "jdbc:x://h:1/" + id);
        }

        @Override
        public void stop() {
            stops++;
        }
    }
}
