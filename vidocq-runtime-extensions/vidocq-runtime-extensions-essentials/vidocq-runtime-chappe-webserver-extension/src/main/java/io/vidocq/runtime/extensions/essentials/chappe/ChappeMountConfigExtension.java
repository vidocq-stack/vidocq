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

import io.vidocq.chappe.api.Handler;
import io.vidocq.chappe.api.WebSocketHandler;
import io.vidocq.runtime.extensions.essentials.chappe.spi.MountConfig;
import io.vidocq.runtime.extensions.essentials.chappe.spi.MountHandlerProvider;
import io.vidocq.runtime.spi.ExtensionContext;
import io.vidocq.runtime.spi.VidocqExtension;
import io.vidocq.runtime.spi.config.VidocqConfig;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * Read the config {@code vidocq.http.mount.<name>.*} and save each mount
 * declared on the {@link ChappeMountPoint} via the {@link MountHandlerProvider}
 * denoted by {@code.type}.
 *
 * <p>Priority 7000 — after programmatic contributing extensions
 * (Cassini: 500, custom apps: generally < 5000) and older than
 * {@code ChappeServerBootstrap} (10,000) which starts the servers.</p>
 *
 * <h3>Configuration format</h3>
 * <pre>{@code
 * vidocq.http.mount.ui.path = /
 * vidocq.http.mount.ui.type = static
 * vidocq.http.mount.ui.classpath = static
 * vidocq.http.mount.ui.cache-in-memory = true
 *
 * vidocq.http.mount.api.path = /api
 * vidocq.http.mount.api.type = restful # standard Jakarta REST
 *
 * vidocq.http.mount.legacy.path = /srv
 * vidocq.http.mount.legacy.type = servlet # standard Jakarta Servlet
 * vidocq.http.mount.legacy.priority = 200 # optional overload
 * vidocq.http.mount.legacy.listener = secured # default: "default"
 *
 * vidocq.http.mount.roomws.path = /ws/rooms/{pin} # WebSocket pattern (RFC 6455)
 * vidocq.http.mount.roomws.type = websocket # built-in; .handler is a WebSocketHandler CDI bean
 * vidocq.http.mount.roomws.handler = com.example.RoomSocket
 * }</pre>
 *
 * <p>The values ​​​​of {@code .type} describe the <b>standard contract</b>
 * (e.g. {@code restful}, {@code servlet}, {@code static}), not one
 * implementation. This leaves the door open for several providers to
 * the same type; a future {@code .impl} will then be able to discriminate.</p>
 *
 * <p>Mounts are recorded in <b>descending priority</b>: one mount
 * with a higher priority wins against another who would share a
 * shorter prefix. The <b>default priority</b> is the length of the
 * normalized prefix, which naturally ensures that {@code /api} wins
 * against {@code /} without having to specify it.</p>
 */
public final class ChappeMountConfigExtension implements VidocqExtension {

    private static final System.Logger LOG = System.getLogger(ChappeMountConfigExtension.class.getName());

    private static final String PREFIX = "vidocq.http.mount.";
    private static final String SUFFIX_PATH = ".path";

    /**
     * Built-in mount type handled here (not via a {@link MountHandlerProvider}, since a WebSocket
     * is registered with {@code router.webSocket(pattern, handler)} rather than mounted as an HTTP
     * {@link Handler}). The handler is a CDI bean named by {@code .handler}, resolved from Vauban.
     */
    private static final String TYPE_WEBSOCKET = "websocket";

    @Override
    public String name() {
        return "chappe-mount-config";
    }

    @Override
    public int priority() {
        return 7_000;
    }

    @Override
    public void onStart(ExtensionContext context) {
        VidocqConfig config = context.config();
        Set<String> mountNames = collectMountNames(config);
        if (mountNames.isEmpty()) {
            return;
        }

        Map<String, MountHandlerProvider> providers = loadProviders();
        List<MountConfig> mounts = new ArrayList<>();

        for (String name : mountNames) {
            String prefix = config.getValue(PREFIX + name + ".path", String.class, "/");
            String listener = config.getValue(PREFIX + name + ".listener", String.class, ChappeListener.DEFAULT);
            String normalizedPrefix = normalizePrefix(prefix);
            int priority = config.getValue(PREFIX + name + ".priority", Integer.class,
                    defaultPriority(normalizedPrefix));
            mounts.add(new MountConfig(name, normalizedPrefix, listener, priority, config, context));
        }
        mounts.sort(Comparator.comparingInt(MountConfig::priority).reversed());

        for (MountConfig m : mounts) {
            String type = m.property("type")
                    .orElseThrow(() -> new IllegalStateException(
                            "Mount '" + m.name() + "' is missing required " + PREFIX + m.name() + ".type"));
            if (TYPE_WEBSOCKET.equals(type)) {
                mountWebSocket(m);
                continue;
            }
            MountHandlerProvider provider = providers.get(type);
            if (provider == null) {
                throw new IllegalStateException(
                        "No MountHandlerProvider registered for type='" + type
                                + "' (mount '" + m.name() + "'). "
                                + "Available types: " + providers.keySet());
            }
            Handler handler = provider.create(m);
            // strip-prefix=false → routing-only mount: the handler sees the full path, so resources
            // with absolute @Path (e.g. MicroProfile Health @Path("/health")) match at the prefix
            // without the path doubling, while sibling prefixes (static /, /api) stay routable.
            boolean stripPrefix = m.property("strip-prefix", Boolean.class, true);
            ChappeMountPoint.instance().mount(m.listener(), m.prefix(), handler, stripPrefix);
            LOG.log(System.Logger.Level.INFO,
                    "Mounted '{0}' (type={1}) on listener={2} prefix={3} priority={4} strip-prefix={5}",
                    m.name(), type, m.listener(), m.prefix().isEmpty() ? "/" : m.prefix(),
                    m.priority(), stripPrefix);
        }
    }

    /**
     * Registers a declarative {@code type=websocket} mount: resolves the {@link WebSocketHandler}
     * CDI bean named by {@code .handler} from the Vauban container and binds it to the {@code .path}
     * pattern (e.g. {@code /ws/rooms/{pin}}) on the mount's listener via {@code router.webSocket()}.
     */
    private static void mountWebSocket(MountConfig m) {
        String pattern = m.prefix();
        String handlerClass = m.property("handler")
                .orElseThrow(() -> new IllegalStateException(
                        "Mount '" + m.name() + "' (type=websocket) requires " + PREFIX + m.name()
                                + ".handler=<fully-qualified WebSocketHandler bean>"));
        var container = m.extensionContext().container();
        Class<?> beanClass;
        try {
            beanClass = Class.forName(handlerClass, false, container.classLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Mount '" + m.name()
                    + "': WebSocket handler class not found: " + handlerClass, e);
        }
        Object bean = container.select(beanClass);
        if (!(bean instanceof WebSocketHandler ws)) {
            throw new IllegalStateException("Mount '" + m.name() + "': " + handlerClass
                    + " must implement " + WebSocketHandler.class.getName());
        }
        ChappeMountPoint.instance().webSocket(m.listener(), pattern, ws);
        LOG.log(System.Logger.Level.INFO,
                "Mounted '{0}' (type=websocket) on listener={1} pattern={2} handler={3}",
                m.name(), m.listener(), pattern, handlerClass);
    }

    private static Set<String> collectMountNames(VidocqConfig config) {
        Set<String> names = new LinkedHashSet<>();
        for (String key : config.getPropertyNames()) {
            if (!key.startsWith(PREFIX) || !key.endsWith(SUFFIX_PATH)) continue;
            String middle = key.substring(PREFIX.length(), key.length() - SUFFIX_PATH.length());
            if (middle.isEmpty() || middle.indexOf('.') >= 0) continue;
            names.add(middle);
        }
        return names;
    }

    private static String normalizePrefix(String raw) {
        if (raw == null || raw.isEmpty() || "/".equals(raw)) return "";
        return raw.endsWith("/") ? raw.substring(0, raw.length() - 1) : raw;
    }

    /**
     * Default priority derived from prefix length: one mount on one
     * Longer prefix should win against a root catch-all. The mount {@code "/"}
     * (normalized prefix {@code ""}) therefore has priority 0; {@code "/api"} takes precedence
     * 4; {@code "/api/v2"} has priority 7. User can override via
     * {@code vidocq.http.mount.<n>.priority=<int>}.
     */
    private static int defaultPriority(String normalizedPrefix) {
        return normalizedPrefix.length();
    }

    private static Map<String, MountHandlerProvider> loadProviders() {
        Map<String, MountHandlerProvider> map = new HashMap<>();
        for (MountHandlerProvider p : ServiceLoader.load(MountHandlerProvider.class)) {
            MountHandlerProvider previous = map.putIfAbsent(p.type(), p);
            if (previous != null) {
                throw new IllegalStateException(
                        "Two MountHandlerProvider register the same type='" + p.type()
                                + "': " + previous.getClass().getName() + " and " + p.getClass().getName());
            }
        }
        return map;
    }
}
