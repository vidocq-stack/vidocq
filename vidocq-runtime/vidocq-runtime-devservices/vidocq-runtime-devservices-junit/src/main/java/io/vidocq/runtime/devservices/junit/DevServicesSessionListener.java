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

import io.vidocq.runtime.devservices.host.ApplicationFiles;
import io.vidocq.runtime.devservices.host.DevServicesException;
import io.vidocq.runtime.devservices.host.DevServicesFlag;
import io.vidocq.runtime.devservices.host.DevServicesSession;
import io.vidocq.runtime.devservices.host.StateFile;

import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Hosts dev services for a whole JUnit Platform test run (spec 2026-09-24-devservices-visibility-run-tests §7):
 * discovered via {@code META-INF/services/org.junit.platform.launcher.LauncherSessionListener}, with no
 * annotation needed in the tests themselves. One set of containers is started in
 * {@link #launcherSessionOpened(LauncherSession)}, shared by every {@code VidocqBootstrap} in the run, and
 * stopped in {@link #launcherSessionClosed(LauncherSession)}.
 *
 * <p><b>Must stay its own jar.</b> Spec §7.1's spike proved that a {@code LauncherSessionListener} compiled
 * into an application's own test sources is folded by Surefire's module-path {@code --patch-module} into the
 * application's named module, where {@code ServiceLoader} can no longer see its {@code META-INF/services}
 * entry — only a listener shipped in its own, separate (unnamed-module) jar is found there. This class must
 * never be copied into, or subclassed from, an application's test sources for that reason.</p>
 */
public final class DevServicesSessionListener implements LauncherSessionListener {

    private static final String CLASSES = "classes";

    /** Opens the session of a base directory and of the directory of the application's classes. */
    private final BiFunction<Path, Path, DevServicesSession> opener;
    private DevServicesSession session;

    /** Used by the JUnit Platform, through {@code META-INF/services}. */
    public DevServicesSessionListener() {
        this((basedir, classes) -> {
            try {
                return DevServicesSession.open(
                        "test",
                        basedir,
                        Map.of(),
                        ApplicationFiles.of(classes),
                        ApplicationFiles.allOf(classes),
                        onTestClasspath(Thread.currentThread().getContextClassLoader()),
                        new ConsoleSystemLogger(System.out));
            } catch (DevServicesException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        });
    }

    /**
     * The test JVM's own class path (spec 2026-09-29-devservice-postgres-kind §5), through {@code loader} — the
     * thread context class loader — or this class's own loader when there is none: whether it holds a class, found as
     * a {@code .class} resource (visible even inside a named module), never loaded.
     */
    static Predicate<String> onTestClasspath(ClassLoader loader) {
        ClassLoader effective = loader != null ? loader : DevServicesSessionListener.class.getClassLoader();
        return className -> effective.getResource(className.replace('.', '/') + ".class") != null;
    }

    /** For {@code vidocq-runtime-devservices-junit}'s own tests, which supply a fake opener. */
    DevServicesSessionListener(BiFunction<Path, Path, DevServicesSession> opener) {
        this.opener = opener;
    }

    /**
     * The directory of the application's classes, where its {@code vidocq.properties} is read: the one the test JVM
     * runs on. That is the first entry of {@code modulePath} (the system property {@code jdk.module.path}), then of
     * {@code classPath} ({@code java.class.path}), that is a directory named {@code classes} under {@code basedir}:
     * {@code target/classes} in a Maven build, the folder an IDE compiles into when it has one of its own
     * ({@code target-ide/classes}), where Maven's folder holds the files of another build, or none
     * (BUG-20261122-03). A folder of another module of the build is not under {@code basedir}, and the test classes
     * are in a folder of another name.
     *
     * <p>Without such an entry, {@code <basedir>/target/classes}: Surefire's class path mode hides its class path
     * behind a jar that holds a manifest only, and its classes are Maven's.</p>
     */
    static Path applicationClasses(Path basedir, String modulePath, String classPath) {
        Path base = real(basedir);
        for (String path : new String[] {modulePath, classPath}) {
            if (path == null) {
                continue;
            }
            for (String entry : path.split(Pattern.quote(File.pathSeparator))) {
                Path classes = classesUnder(base, entry);
                if (classes != null) {
                    return classes;
                }
            }
        }
        return basedir.resolve("target").resolve(CLASSES);
    }

    /** {@code entry} as a path when it is a directory named {@code classes} under {@code base}, or null. */
    private static Path classesUnder(Path base, String entry) {
        if (entry.isEmpty()) {
            return null;
        }
        try {
            // A relative entry is read from the working directory, as the JVM read it.
            Path directory = Path.of(entry).toAbsolutePath().normalize();
            return directory.getFileName() != null && CLASSES.equals(directory.getFileName().toString())
                    && Files.isDirectory(directory) && real(directory).startsWith(base) ? directory : null;
        } catch (InvalidPathException _) {
            return null;
        }
    }

    /** {@code path} with its links followed, so that two spellings of a folder compare; as it is when unreadable. */
    private static Path real(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException _) {
            return path.toAbsolutePath().normalize();
        }
    }

    @Override
    public void launcherSessionOpened(LauncherSession launcherSession) {
        Path basedir = Path.of(System.getProperty("basedir", System.getProperty("user.dir")));
        Path classes = applicationClasses(basedir, System.getProperty("jdk.module.path"),
                System.getProperty("java.class.path"));
        Function<String, Optional<String>> files = ApplicationFiles.of(classes);
        if (!DevServicesFlag.enabled(Optional.ofNullable(System.getProperty(DevServicesFlag.KEY)), files, true)) {
            return;
        }
        session = opener.apply(basedir, classes);
        session.foldInto(System.getProperties());
        System.setProperty(StateFile.PROPERTY, session.stateFile().toAbsolutePath().toString());
    }

    @Override
    public void launcherSessionClosed(LauncherSession launcherSession) {
        if (session != null) {
            session.close();
            session = null;
        }
    }
}
