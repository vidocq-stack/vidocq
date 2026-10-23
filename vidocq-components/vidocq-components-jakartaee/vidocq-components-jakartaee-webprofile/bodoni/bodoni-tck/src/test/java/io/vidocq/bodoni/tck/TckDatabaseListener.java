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
package io.vidocq.bodoni.tck;

import java.io.IOException;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

/**
 * Starts the TCK database once per test JVM, before the first TCK class runs, and publishes the four
 * {@code jstl.db.*} system properties the TCK clients send to the pages. The port is known only at run
 * time, so the properties cannot be static in the POM.
 *
 * <p>It acts only when {@value #DDL_PROPERTY} is set, which only the {@code tck-official} profile does:
 * the runner's own tests under {@code -Ptck} never need the official zip. The database lives as long as
 * the JVM, like the formal TCK's single {@code initdb}, and a shutdown hook closes it.
 */
public final class TckDatabaseListener implements LauncherSessionListener {
    /** The system property naming the official Derby DDL, set by the {@code tck-official} profile. */
    public static final String DDL_PROPERTY = "bodoni.tck.ddl";

    private static final AtomicBoolean OPENED = new AtomicBoolean();

    /** Instantiated by the JUnit Platform through {@code META-INF/services}. */
    public TckDatabaseListener() {
        // Stateless: the once-per-JVM guard is static.
    }

    @Override
    public void launcherSessionOpened(LauncherSession session) {
        if (!OPENED.compareAndSet(false, true)) {
            return;
        }
        Optional<TckDatabase> database;
        try {
            database = startIfConfigured(System.getProperty(DDL_PROPERTY));
        } catch (IOException | SQLException failure) {
            throw new IllegalStateException("The TCK database did not start", failure);
        }
        database.ifPresent(started -> Runtime.getRuntime().addShutdownHook(
                Thread.ofPlatform().name("bodoni-tck-database-close").unstarted(() -> closeAtExit(started))));
    }

    /**
     * Starts the database from {@code ddl} and publishes its client properties; empty when {@code ddl}
     * is {@code null} or blank.
     *
     * @throws IllegalStateException when {@code ddl} names no file, so that a run without the DDL stops
     *         instead of failing every sql and compat test in its setup
     */
    static Optional<TckDatabase> startIfConfigured(String ddl) throws IOException, SQLException {
        return startIfConfigured(ddl, TckDatabase.NAME);
    }

    /**
     * {@link #startIfConfigured(String)} under another in-memory database name, for the runner's own
     * tests (see {@link TckDatabase#start(Path, String)}).
     */
    static Optional<TckDatabase> startIfConfigured(String ddl, String name) throws IOException, SQLException {
        if (ddl == null || ddl.isBlank()) {
            return Optional.empty();
        }
        Path path = Path.of(ddl);
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException(DDL_PROPERTY + " names no file: " + ddl
                    + "; run ./run-official-tck-tags-3.0.sh, which fetches the official Derby DDL");
        }
        TckDatabase database = TckDatabase.start(path, name);
        database.clientProperties().forEach(System::setProperty);
        return Optional.of(database);
    }

    private static void closeAtExit(TckDatabase database) {
        try {
            database.close();
        } catch (SQLException failure) {
            System.getLogger(TckDatabaseListener.class.getName())
                    .log(Level.WARNING, "The TCK database did not close cleanly", failure);
        }
    }
}
