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
package io.vidocq.runtime.devservices.spi;

import java.util.Map;

/**
 * A dev-mode service provider — provisions an external dependency (a database, an identity
 * provider, a broker, …) for the duration of a {@code vidocq:dev} session and surfaces the
 * coordinates the application needs as a map of {@code key=value} pairs.
 *
 * <p><b>Where this runs.</b> Implementations are discovered via {@link java.util.ServiceLoader}
 * on the <b>Maven plugin classpath</b> (fed by the application's {@code <plugin><dependencies>}),
 * and {@link #start(DevServiceContext)}/{@link #stop()} are invoked inside the plugin JVM — never
 * inside the forked application JVM. The returned properties are injected into the child as
 * {@code -D} system properties. This keeps the heavy dev machinery (e.g. Testcontainers) entirely
 * off the runtime module-path and out of the AOT/native image (see {@code DEBUGMODE.md §10}).</p>
 *
 * <p><b>Lifecycle.</b> A provider is started once, before the first child JVM is forked, and stopped
 * once, when the dev session ends (Ctrl+C). It survives source reloads: a recompile/restart of the
 * child does not touch the running service.</p>
 *
 * <p>Implementations should be safe to {@link #stop()} more than once.</p>
 */
public interface DevService {

    /**
     * Short, stable identifier used for logging and ordering diagnostics — e.g. {@code "postgres"},
     * {@code "keycloak"}. Unique across the providers active in a session.
     */
    String id();

    /**
     * Start order, ascending (lower starts first). Lets a provider that others depend on come up
     * earlier. Defaults to {@code 1000}.
     */
    default int order() {
        return 1000;
    }

    /**
     * Whether this provider should run for the current project. Mirrors the Quarkus DevServices
     * rule: a provider opts out when the application has already configured the dependency itself
     * (e.g. the Postgres provider returns {@code false} when {@code vidocq.pool.url} is given explicitly), or when
     * the application does not use it (the Postgres provider for an application on H2); {@link #skipReason} then
     * says why.
     *
     * @param ctx access to the resolved application configuration
     * @return {@code true} to start this provider, {@code false} to skip it
     */
    boolean appliesWhen(DevServiceContext ctx);

    /**
     * Why this provider does not start, when {@link #appliesWhen} is {@code false}: one line, no secret. The host
     * logs it as {@code DevService '<id>' not started: <reason>}, keeps it in the state file and shows it in the
     * startup report and the dev console; {@code null}, the default, when there is none to give (the host then logs
     * {@code skipped (already configured)}, as before).
     *
     * @param ctx the context {@link #appliesWhen} was given
     * @return the reason, or {@code null}
     */
    default String skipReason(DevServiceContext ctx) {
        return null;
    }

    /**
     * Provision the service and return the system properties to inject into the child JVM. Blocks
     * until the service is ready. Keys collide-resolve in favour of explicitly configured values:
     * the goal folds these in with {@code putIfAbsent}, so a user-set {@code -D} or
     * {@code vidocq.dev.systemProperties} entry always wins.
     *
     * @param ctx access to configuration, the project base dir and a logger
     * @return the {@code key -> value} pairs to expose to the application (never {@code null})
     * @throws Exception if the service could not be started (the goal aborts with a clear message)
     */
    Map<String, String> start(DevServiceContext ctx) throws Exception;

    /**
     * Release the provisioned service. Must be idempotent — it may be called from a shutdown hook
     * and again from the goal's {@code finally} block.
     */
    void stop();

    /**
     * What this provider started, for the application's startup report and dev console. Called once, after
     * {@link #start}, with what {@code start} returned. The default names the provider and its keys; a provider
     * that runs a container should say which image and where it listens.
     *
     * @param injected what {@link #start} returned
     * @return the state, never {@code null}
     */
    default DevServiceState describe(Map<String, String> injected) {
        return DevServiceState.minimal(id(), injected.keySet());
    }
}
