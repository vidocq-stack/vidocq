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
 *
 * <p>The manager also keeps which provider supplied each key ({@link #providers()}): the application cannot
 * tell a dev-service value from a hand-set {@code -D}, so the goal marks the ones it passes on. {@link #states()}
 * exposes what each started provider reported about itself, for the application's startup report and dev
 * console.</p>
 */
public final class DevServiceManager implements AutoCloseable {

    private final List<DevService> started = new ArrayList<>();
    private final List<Map<String, String>> outputs = new ArrayList<>();
    private final Map<String, String> collected = new LinkedHashMap<>();
    private final Map<String, String> providers = new LinkedHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private DevServiceManager() {}

    /** Production entry point: discover providers via ServiceLoader on the plugin realm. */
    public static DevServiceManager start(DefaultDevServiceContext ctx, System.Logger log) throws DevServicesException {
        List<DevService> providers = new ArrayList<>();
        ServiceLoader.load(DevService.class, DevServiceManager.class.getClassLoader())
                .forEach(providers::add);
        return start(providers, ctx, log);
    }

    /** Core loop, exposed package-private so tests can inject providers without a {@code META-INF/services}. */
    static DevServiceManager start(List<DevService> providers, DefaultDevServiceContext ctx, System.Logger log)
            throws DevServicesException {
        DevServiceManager mgr = new DevServiceManager();
        List<DevService> ordered = new ArrayList<>(providers);
        ordered.sort(Comparator.comparingInt(DevService::order));
        for (DevService p : ordered) {
            try {
                if (!p.appliesWhen(ctx)) {
                    log.log(System.Logger.Level.INFO, "DevService '" + p.id() + "' skipped (already configured)");
                    continue;
                }
                log.log(System.Logger.Level.INFO, "DevService '" + p.id() + "' starting…");
                Map<String, String> props = p.start(ctx);
                mgr.started.add(p);
                mgr.outputs.add(props != null ? props : Map.of());
                if (props != null && !props.isEmpty()) {
                    mgr.collected.putAll(props);
                    props.keySet().forEach(key -> mgr.providers.put(key, p.id()));
                    ctx.merge(props);
                }
                log.log(System.Logger.Level.INFO, "DevService '" + p.id() + "' started");
            } catch (Exception e) {
                mgr.close(); // roll back the providers already started, in reverse order
                throw new DevServicesException("DevService '" + p.id() + "' failed to start: "
                        + e.getMessage() + " — set -Dvidocq.dev.devServices=false to skip", e);
            }
        }
        return mgr;
    }

    /** The {@code key=value} pairs to expose to the child JVM (provider outputs only). */
    public Map<String, String> collectedProperties() {
        return Collections.unmodifiableMap(collected);
    }

    /**
     * The {@linkplain DevService#id() id} of the provider that supplied each key of {@link #collectedProperties()},
     * such as {@code vidocq.pool.url -> postgres}. A key two providers supplied is the later one's, as its value is.
     */
    public Map<String, String> providers() {
        return Collections.unmodifiableMap(providers);
    }

    /**
     * What each started provider reported about itself, in start order, from
     * {@link DevService#describe(Map)} called with what {@link DevService#start} returned. A provider whose
     * {@code describe} throws falls back to {@link DevServiceState#minimal}.
     */
    public List<DevServiceState> states() {
        List<DevServiceState> out = new ArrayList<>(started.size());
        for (int i = 0; i < started.size(); i++) {
            DevService p = started.get(i);
            Map<String, String> injected = outputs.get(i);
            DevServiceState state;
            try {
                state = p.describe(injected);
            } catch (RuntimeException e) {
                state = DevServiceState.minimal(p.id(), injected.keySet());
            }
            out.add(state);
        }
        return Collections.unmodifiableList(out);
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
