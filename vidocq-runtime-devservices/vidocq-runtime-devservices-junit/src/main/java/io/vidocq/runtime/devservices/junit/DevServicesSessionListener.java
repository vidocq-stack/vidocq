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

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

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

    private final Function<Path, DevServicesSession> opener;
    private DevServicesSession session;

    /** Used by the JUnit Platform, through {@code META-INF/services}. */
    public DevServicesSessionListener() {
        this(basedir -> {
            try {
                Path classes = basedir.resolve("target").resolve("classes");
                return DevServicesSession.open(
                        "test",
                        basedir,
                        Map.of(),
                        ApplicationFiles.of(classes),
                        ApplicationFiles.allOf(classes),
                        onTestClasspath(Thread.currentThread().getContextClassLoader()),
                        System.getLogger("vidocq.test.devservices"));
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
    DevServicesSessionListener(Function<Path, DevServicesSession> opener) {
        this.opener = opener;
    }

    @Override
    public void launcherSessionOpened(LauncherSession launcherSession) {
        Path basedir = Path.of(System.getProperty("basedir", System.getProperty("user.dir")));
        Function<String, Optional<String>> files = ApplicationFiles.of(basedir.resolve("target").resolve("classes"));
        if (!DevServicesFlag.enabled(Optional.ofNullable(System.getProperty(DevServicesFlag.KEY)), files, true)) {
            return;
        }
        session = opener.apply(basedir);
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
