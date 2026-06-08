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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Starts a {@link Server} Chappe by {@link ChappeListener} declared, after all
 * contributing extensions called {@link ChappeMountPoint#mount}.
 * <p>
 * Priority 10,000: runs after all contributors (REST, Servlet, etc.).
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

    private final List<Server> servers = new ArrayList<>();
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
    public void onStart(ExtensionContext context) {
        this.mountPoint = ChappeMountPoint.instance();
        List<ChappeListener> listeners = resolveListeners(context.config());

        for (ChappeListener l : listeners) {
            mountPoint.declareListener(l);
        }
        mountPoint.freeze();

        for (Runnable h : mountPoint.beforeStartHooks()) {
            runHookSilently(h, "beforeStart");
        }

        for (ChappeListener l : listeners) {
            Router router = mountPoint.buildRouter(l.name());
            Server server = Server.builder()
                    .host(l.host())
                    .port(l.port())
                    .handler(router)
                    .build();
            server.start();
            servers.add(server);
            LOG.log(System.Logger.Level.INFO,
                    "Chappe listener '" + l.name() + "' started on http://"
                            + displayHost(l.host()) + ":" + l.port() + "/");
        }
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
            String hostKey = "vidocq.chappe.listener." + name + ".host";
            String portKey = "vidocq.chappe.listener." + name + ".port";
            String host = config.getValue(hostKey, String.class, "0.0.0.0");
            int port = config.getValue(portKey, Integer.class,
                    ChappeListener.DEFAULT.equals(name) ? 8080 : -1);
            if (port < 0) {
                throw new IllegalStateException(
                        "Missing port configuration for listener '" + name + "' (" + portKey + ")");
            }
            out.add(ChappeListener.http(name, host, port));
        }
        return out;
    }

    private static String displayHost(String host) {
        if (host == null) return "localhost";
        return switch (host) {
            case "0.0.0.0", "::", "::0", "0:0:0:0:0:0:0:0" -> "localhost";
            default -> host;
        };
    }

    private static void runHookSilently(Runnable r, String phase) {
        try {
            r.run();
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Chappe " + phase + " hook failed", e);
        }
    }
}
