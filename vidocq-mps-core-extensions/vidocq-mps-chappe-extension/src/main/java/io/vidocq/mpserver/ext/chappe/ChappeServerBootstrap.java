package io.vidocq.mpserver.ext.chappe;

import io.vidocq.chappe.api.Router;
import io.vidocq.chappe.api.Server;
import io.vidocq.mpserver.spi.ExtensionContext;
import io.vidocq.mpserver.spi.VidocqExtension;
import io.vidocq.mpserver.spi.config.VidocqConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Démarre un {@link Server} Chappe par {@link ChappeListener} déclaré, après que toutes
 * les extensions contributrices ont appelé {@link ChappeMountPoint#mount}.
 * <p>
 * Priorité 10 000 : tourne après tous les contributeurs (REST, Servlet, ...).
 * </p>
 *
 * <h3>Configuration</h3>
 * <ul>
 *   <li>{@code vidocq.chappe.listeners} — liste CSV des listeners (défaut : {@code default})</li>
 *   <li>{@code vidocq.chappe.listener.<name>.host} — hôte (défaut : {@code 0.0.0.0})</li>
 *   <li>{@code vidocq.chappe.listener.<name>.port} — port (défaut : 8080 pour {@code default}, obligatoire sinon)</li>
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
                            + l.host() + ":" + l.port() + "/");
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

    private static void runHookSilently(Runnable r, String phase) {
        try {
            r.run();
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Chappe " + phase + " hook failed", e);
        }
    }
}
