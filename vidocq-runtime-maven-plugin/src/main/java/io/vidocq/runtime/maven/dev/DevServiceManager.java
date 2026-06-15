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

import io.vidocq.runtime.devservices.spi.DevService;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Discovers, starts and stops the {@link DevService} providers contributed to a {@code vidocq:dev}
 * run. Providers are loaded by {@link ServiceLoader} from the <b>plugin realm</b> (which carries the
 * application's {@code <plugin><dependencies>}), so they — and their Testcontainers machinery — live
 * exclusively in the Maven JVM, never on the forked application's module-path (see {@code DEBUGMODE.md §10}).
 *
 * <p>Providers start once, ordered by {@link DevService#order()}, before the first child JVM fork; an
 * applicable provider that fails aborts the whole run after rolling back the ones already started. The
 * collected properties are injected into the child as {@code -D} values; {@link #close()} stops every
 * started provider in reverse order and is idempotent (shutdown hook + {@code finally}).</p>
 */
final class DevServiceManager implements AutoCloseable {

    private final List<DevService> started = new ArrayList<>();
    private final Map<String, String> collected = new LinkedHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private DevServiceManager() {}

    /** Production entry point: discover providers via ServiceLoader on the plugin realm. */
    static DevServiceManager start(DefaultDevServiceContext ctx, Log log) throws MojoExecutionException {
        List<DevService> providers = new ArrayList<>();
        ServiceLoader.load(DevService.class, DevServiceManager.class.getClassLoader())
                .forEach(providers::add);
        return start(providers, ctx, log);
    }

    /** Core loop, exposed package-private so tests can inject providers without a {@code META-INF/services}. */
    static DevServiceManager start(List<DevService> providers, DefaultDevServiceContext ctx, Log log)
            throws MojoExecutionException {
        DevServiceManager mgr = new DevServiceManager();
        List<DevService> ordered = new ArrayList<>(providers);
        ordered.sort(Comparator.comparingInt(DevService::order));
        for (DevService p : ordered) {
            try {
                if (!p.appliesWhen(ctx)) {
                    log.info("DevService '" + p.id() + "' skipped (already configured)");
                    continue;
                }
                log.info("DevService '" + p.id() + "' starting…");
                Map<String, String> props = p.start(ctx);
                mgr.started.add(p);
                if (props != null && !props.isEmpty()) {
                    mgr.collected.putAll(props);
                    ctx.merge(props);
                }
                log.info("DevService '" + p.id() + "' started");
            } catch (Exception e) {
                mgr.close(); // roll back the providers already started, in reverse order
                throw new MojoExecutionException("DevService '" + p.id() + "' failed to start: "
                        + e.getMessage() + " — set -Dvidocq.dev.devServices=false to skip", e);
            }
        }
        return mgr;
    }

    /** The {@code key=value} pairs to expose to the child JVM (provider outputs only). */
    Map<String, String> collectedProperties() {
        return Collections.unmodifiableMap(collected);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (int i = started.size() - 1; i >= 0; i--) {
            try {
                started.get(i).stop();
            } catch (RuntimeException e) {
                // best-effort teardown — keep stopping the remaining providers
            }
        }
    }
}
