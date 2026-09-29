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
package io.vidocq.runtime.devservices.host;

import io.vidocq.runtime.devservices.spi.DevService;
import io.vidocq.runtime.devservices.spi.DevServiceState;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A running {@code vidocq:dev}/{@code vidocq:run}/test dev-services session: starts the {@link DevService}
 * providers through {@link DevServiceManager}, writes the state file (spec §4.2) and the connection
 * block/properties file, and stops everything on {@link #close()}. Both Maven goals and the JUnit host share
 * this one class (spec §6: "same code").
 */
public final class DevServicesSession implements AutoCloseable {

    /**
     * Prefix of the markers that say which dev service provided a key the application was given:
     * {@code vidocq.dev.provided.<key>=<provider id>}.
     */
    public static final String PROVIDED_PREFIX = "vidocq.dev.provided.";

    private final DevServiceManager mgr;
    private final Path stateFile;
    private final String host;
    private final Instant startedAt;
    private final System.Logger log;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private DevServicesSession(
            DevServiceManager mgr, Path stateFile, String host, Instant startedAt, System.Logger log) {
        this.mgr = mgr;
        this.stateFile = stateFile;
        this.host = host;
        this.startedAt = startedAt;
        this.log = log;
    }

    /**
     * Production entry point: builds the context, discovers providers via {@code ServiceLoader} — through
     * {@link DevServiceManager#start(DefaultDevServiceContext, System.Logger)} — and uses the system clock.
     */
    public static DevServicesSession open(String host, Path basedir, Map<String, String> seed,
            Function<String, Optional<String>> applicationFiles, System.Logger log) throws DevServicesException {
        return open(host, basedir, seed, applicationFiles, key -> Optional.empty(), className -> false, log);
    }

    /**
     * {@link #open(String, Path, Map, Function, System.Logger)}, the providers also told what the application is
     * configured for (spec 2026-09-29-devservice-postgres-kind §5): every value of its files
     * ({@link ApplicationFiles#allOf}) and whether its class path holds a class ({@link ApplicationClasspath}).
     */
    public static DevServicesSession open(String host, Path basedir, Map<String, String> seed,
            Function<String, Optional<String>> applicationFiles, Function<String, Optional<String>> applicationValues,
            Predicate<String> applicationClasspath, System.Logger log) throws DevServicesException {
        DefaultDevServiceContext ctx =
                new DefaultDevServiceContext(basedir, seed, applicationFiles, applicationValues, applicationClasspath);
        DevServiceManager mgr = DevServiceManager.start(ctx, log);
        return open(host, basedir, mgr, log, Clock.systemUTC());
    }

    /** Core path, exposed package-private so tests can inject providers without a {@code META-INF/services}. */
    static DevServicesSession open(String host, Path basedir, List<DevService> providers,
            DefaultDevServiceContext ctx, System.Logger log, Clock clock) throws DevServicesException {
        DevServiceManager mgr = DevServiceManager.start(providers, ctx, log);
        return open(host, basedir, mgr, log, clock);
    }

    /**
     * Opens a session on the given providers, with an empty seed and the system clock.
     *
     * <p><b>For tests only.</b> Public for {@code vidocq-runtime-devservices-junit}'s tests, which supply their
     * own providers rather than relying on {@code ServiceLoader} discovery.</p>
     */
    public static DevServicesSession forTesting(String host, Path basedir, List<DevService> providers,
            System.Logger log) throws DevServicesException {
        DefaultDevServiceContext ctx = new DefaultDevServiceContext(basedir, Map.of());
        return open(host, basedir, providers, ctx, log, Clock.systemUTC());
    }

    private static DevServicesSession open(String host, Path basedir, DevServiceManager mgr, System.Logger log,
            Clock clock) throws DevServicesException {
        Path stateFile = stateFileOf(basedir);
        try {
            Instant startedAt = clock.instant();
            List<DevServiceState> states = mgr.states();
            StateFile.write(stateFile, StateFile.json(host, "running", startedAt, states, mgr.collectedProperties()));
            reportConnectionInformation(basedir, mgr.collectedProperties(), log);
            return new DevServicesSession(mgr, stateFile, host, startedAt, log);
        } catch (IOException e) {
            mgr.close();
            throw new DevServicesException(
                    "Cannot write the dev services state file " + stateFile + ": " + e.getMessage(), e);
        } catch (Throwable t) {
            // Nothing holds the session yet, so nothing else could stop the containers: stop them, then go on.
            mgr.close();
            throw t;
        }
    }

    /**
     * Folds {@link #injected()} into {@code target}, the properties the application will see: a key already there
     * — an explicit {@code -D}, a {@code vidocq.dev.systemProperties} entry, a system property — keeps its value,
     * and only a key actually taken from a provider gets its {@code vidocq.dev.provided.<key>=<provider id>}
     * marker, so the application never presents a hand-set value as a dev service's.
     */
    public void foldInto(Map<? super String, ? super String> target) {
        fold(injected(), providers(), target);
    }

    /** {@link #foldInto(Map)}'s rule, for {@code injected}/{@code providers} maps held without a session. */
    public static void fold(Map<String, String> injected, Map<String, String> providers,
            Map<? super String, ? super String> target) {
        injected.forEach((key, value) -> {
            if (value != null && target.putIfAbsent(key, value) == null) {
                String provider = providers.get(key);
                if (provider != null) {
                    target.putIfAbsent(PROVIDED_PREFIX + key, provider);
                }
            }
        });
    }

    /** The collected {@code key -> value} pairs injected by every started provider. */
    public Map<String, String> injected() {
        return mgr.collectedProperties();
    }

    /** The {@linkplain DevService#id() id} of the provider that supplied each key of {@link #injected()}. */
    public Map<String, String> providers() {
        return mgr.providers();
    }

    /** {@code <basedir>/target/vidocq-dev-services.json}. */
    public Path stateFile() {
        return stateFile;
    }

    /**
     * Stops the providers, then rewrites the state file with {@code "state":"stopped"}. Idempotent — a second or
     * later call is a no-op. A failure while stopping or rewriting the file is logged, never thrown: {@code close}
     * runs from a shutdown hook or a {@code finally} block, where nothing could act on a thrown exception anyway.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            mgr.close();
        } catch (RuntimeException e) {
            log.log(System.Logger.Level.WARNING, "Stopping the dev services threw " + e.getClass().getName());
        }
        try {
            StateFile.write(stateFile,
                    StateFile.json(host, "stopped", startedAt, mgr.states(), mgr.collectedProperties()));
        } catch (IOException e) {
            log.log(System.Logger.Level.WARNING, "Could not rewrite " + stateFile + " as stopped: " + e.getMessage());
        } catch (RuntimeException e) {
            log.log(System.Logger.Level.WARNING, "Could not rewrite " + stateFile + " as stopped: "
                    + e.getClass().getName());
        }
    }

    private static Path stateFileOf(Path basedir) {
        return basedir.resolve("target").resolve(StateFile.FILE_NAME);
    }

    /**
     * Logs the {@code Connection information} block for every dev-provisioned datasource and writes the
     * coordinates to {@code target/vidocq-dev-services.properties}, so an external SQL client can reach the dev
     * databases (moved here from {@code VidocqDevMojo} — spec §6, "same code"). No-op when no datasource was
     * provisioned (e.g. only Keycloak ran). A failure to write the properties file is logged, not fatal: the
     * state file already carries what the application needs.
     */
    private static void reportConnectionInformation(Path basedir, Map<String, String> collected, System.Logger log) {
        List<String> lines = DevServicesReport.consoleLines(collected);
        if (lines.isEmpty()) {
            return;
        }
        lines.forEach(line -> log.log(System.Logger.Level.INFO, line));
        Path file = basedir.resolve("target").resolve("vidocq-dev-services.properties");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, DevServicesReport.fileContent(collected));
            log.log(System.Logger.Level.INFO, "Connection information written to " + file);
        } catch (IOException e) {
            log.log(System.Logger.Level.WARNING, "Could not write " + file + ": " + e.getMessage());
        }
    }
}
