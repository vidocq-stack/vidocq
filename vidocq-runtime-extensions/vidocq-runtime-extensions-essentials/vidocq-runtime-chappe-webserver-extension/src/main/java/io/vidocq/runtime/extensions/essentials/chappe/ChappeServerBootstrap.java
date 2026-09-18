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
package io.vidocq.runtime.extensions.essentials.chappe;

import io.vidocq.chappe.api.Router;
import io.vidocq.chappe.api.Server;
import io.vidocq.runtime.spi.ExtensionContext;
import io.vidocq.runtime.spi.VidocqExtension;
import io.vidocq.runtime.spi.config.VidocqConfig;

import java.net.BindException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Starts a {@link Server} Chappe by {@link ChappeListener} declared, after all
 * contributing extensions called {@link ChappeMountPoint#mount}.
 * <p>
 * Priority 10,000: runs after all contributors (REST, Servlet, etc.).
 * </p>
 * <p>
 * The listeners are those an extension declared with
 * {@link ChappeMountPoint#declareListener(ChappeListener, ListenerOptions)}, then those of the configuration,
 * each started once, in that order. Each one logs the address it bound, {@code http://127.0.0.1:43127/} for
 * a port {@code 0}, at INFO or, for a {@link ListenerOptions#quiet() quiet} one, at DEBUG; the address is
 * read once, after the start, and handed to {@link ListenerOptions#onBound() onBound}. A taken port fails
 * the boot, unless the declaration {@link ListenerOptions#anyPortWhenTaken() allows any port}: the listener
 * then binds a free port, with a WARNING naming both.
 * </p>
 *
 * <h3>Configuration</h3>
 * <ul>
 *   <li>{@code vidocq.chappe.listeners} — CSV list of listeners (default: {@code default})</li>
 *   <li>{@code vidocq.chappe.listener.<name>.host} — host (default: {@code 0.0.0.0})</li>
 *   <li>{@code vidocq.chappe.listener.<name>.port} — port (default: 8080 for {@code default}, required otherwise)</li>
 * </ul>
 */
public final class ChappeServerBootstrap implements VidocqExtension {

    private static final System.Logger LOG = System.getLogger(ChappeServerBootstrap.class.getName());

    /**
     * Published aliases for the listener named {@code default}. They are the discoverable names —
     * the CLI ({@code vidocq start --port}), every scaffolded {@code vidocq.properties} and the
     * reference documentation all use them — so the runtime honours them rather than ignoring
     * them silently. The explicit {@code vidocq.chappe.listener.default.*} key always wins, and the
     * aliases back no other listener.
     */
    static final String HTTP_HOST_ALIAS = "vidocq.http.host";
    static final String HTTP_PORT_ALIAS = "vidocq.http.port";

    private final List<Server> servers = new ArrayList<>();
    /** The address each listener bound, by name, read once after its start: a stopped server forgets it. */
    private final Map<String, InetSocketAddress> boundAddresses = new LinkedHashMap<>();
    private ChappeMountPoint mountPoint;

    @Override
    public String name() {
        return "chappe-bootstrap";
    }

    @Override
    public int priority() {
        return 10_000;
    }

    @Override
    public java.util.Set<String> configKeys() {
        return java.util.Set.of(
                "vidocq.chappe.listeners",
                "vidocq.chappe.listener.*",
                HTTP_HOST_ALIAS,
                HTTP_PORT_ALIAS,
                // Claimed by ChappeMountConfigExtension, declared here so the shared vidocq.http.
                // namespace is audited as a whole rather than half-claimed.
                "vidocq.http.mount.*");
    }

    @Override
    public void onStart(ExtensionContext context) {
        this.mountPoint = ChappeMountPoint.instance();

        // after the extensions' own listeners: a name the configuration shares with one of them fails here
        for (ChappeListener l : resolveListeners(context.config())) {
            mountPoint.declareListener(l);
        }
        mountPoint.freeze();

        for (Runnable h : mountPoint.beforeStartHooks()) {
            runHookSilently(h, "beforeStart");
        }

        for (ChappeMountPoint.Declaration d : mountPoint.declarations()) {
            start(d.listener(), d.options(), mountPoint.buildRouter(d.listener().name()));
        }
    }

    /** Starts one listener, on a free port when its own is taken and its options allow it, then says where. */
    private void start(ChappeListener l, ListenerOptions options, Router router) {
        Server server;
        boolean fellBack = false;
        try {
            server = startServer(l, l.port(), options, router);
        } catch (RuntimeException failed) {
            if (!options.anyPortWhenTaken() || l.port() == 0 || !portTaken(failed)) {
                throw failed;
            }
            try {
                server = startServer(l, 0, options, router);
            } catch (RuntimeException alsoFailed) {
                failed.addSuppressed(alsoFailed);
                throw failed;
            }
            fellBack = true;
        }
        servers.add(server);
        InetSocketAddress bound = server.localAddress();
        boundAddresses.put(l.name(), bound);

        if (fellBack) {
            LOG.log(System.Logger.Level.WARNING, "Chappe listener '" + l.name() + "': port " + l.port()
                    + " is taken, listening on port " + bound.getPort() + " instead");
        }
        LOG.log(options.quiet() ? System.Logger.Level.DEBUG : System.Logger.Level.INFO,
                "Chappe listener '" + l.name() + "' started on " + ChappeListener.httpUrl(bound));

        if (options.onBound() != null) {
            try {
                options.onBound().accept(bound);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING,
                        "Chappe listener '" + l.name() + "': its onBound callback failed", e);
            }
        }
    }

    private static Server startServer(ChappeListener l, int port, ListenerOptions options, Router router) {
        Server.Builder builder = Server.builder()
                .host(l.host())
                .port(port)
                .handler(router);
        if (options.shutdownGracePeriod() != null) {
            builder.shutdownGracePeriod(options.shutdownGracePeriod());
        }
        Server server = builder.build();
        server.start();
        return server;
    }

    /** Whether a start failed because the address is in use, which Chappe reports only as its cause. */
    private static boolean portTaken(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof BindException) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void onStop() {
        List<Server> reversed = new ArrayList<>(servers);
        Collections.reverse(reversed);
        for (Server s : reversed) {
            try {
                s.stop();
            } catch (Exception e) {
                LOG.log(System.Logger.Level.ERROR, "Error stopping Chappe server", e);
            }
        }
        servers.clear();
        boundAddresses.clear();

        if (mountPoint != null) {
            for (Runnable h : mountPoint.afterStopHooks()) {
                runHookSilently(h, "afterStop");
            }
        }
        ChappeMountPoint.uninstall();
    }

    List<ChappeListener> resolveListeners(VidocqConfig config) {
        List<String> names = config.getValues("vidocq.chappe.listeners", String.class);
        if (names.isEmpty()) {
            names = List.of(ChappeListener.DEFAULT);
        }
        List<ChappeListener> out = new ArrayList<>(names.size());
        for (String raw : names) {
            String name = raw.trim();
            if (name.isEmpty()) continue;
            boolean isDefault = ChappeListener.DEFAULT.equals(name);
            String hostKey = "vidocq.chappe.listener." + name + ".host";
            String portKey = "vidocq.chappe.listener." + name + ".port";

            String host = config.getValue(hostKey, String.class)
                    .or(() -> isDefault ? config.getValue(HTTP_HOST_ALIAS, String.class) : Optional.empty())
                    .orElse("0.0.0.0");
            int port = config.getValue(portKey, Integer.class)
                    .or(() -> isDefault ? config.getValue(HTTP_PORT_ALIAS, Integer.class) : Optional.empty())
                    .orElse(isDefault ? 8080 : -1);
            if (port < 0) {
                throw new IllegalStateException(
                        "Missing port configuration for listener '" + name + "' (" + portKey + ")");
            }
            out.add(ChappeListener.http(name, host, port));
        }
        return out;
    }

    /** The servers started, in start order (tests). */
    List<Server> servers() {
        return List.copyOf(servers);
    }

    /** The address each listener bound, by name, in start order (tests). */
    Map<String, InetSocketAddress> boundAddresses() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(boundAddresses));
    }

    private static void runHookSilently(Runnable r, String phase) {
        try {
            r.run();
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Chappe " + phase + " hook failed", e);
        }
    }
}
