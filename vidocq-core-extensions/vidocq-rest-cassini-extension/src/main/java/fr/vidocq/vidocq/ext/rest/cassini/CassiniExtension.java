package fr.vidocq.vidocq.ext.rest.cassini;

import fr.vidocq.vauban.core.container.VaubanContainerBuilder;
import fr.vidocq.vidocq.ext.chappe.ChappeListener;
import fr.vidocq.vidocq.spi.ExtensionContext;
import fr.vidocq.vidocq.spi.VidocqConfiguration;
import fr.vidocq.vidocq.spi.VidocqExtension;

/**
 * Extension Cassini — runtime Jakarta RESTful Web Services 4.0 monté sur le
 * moteur HTTP Chappe.
 *
 * <p>Priorité 500 : tourne après {@code ChappeEngineExtension} et avant
 * {@code ChappeServerBootstrap}, afin de contribuer un handler JAX-RS au
 * {@code ChappeMountPoint} via un bridge dédié.</p>
 *
 * <h3>Configuration</h3>
 * <ul>
 *   <li>{@code vidocq.rest.context-path} — préfixe de montage (défaut : {@code /})</li>
 *   <li>{@code vidocq.rest.listener} — listener Chappe cible (défaut : {@link ChappeListener#DEFAULT})</li>
 * </ul>
 */
public final class CassiniExtension implements VidocqExtension {

    private static final System.Logger LOG = System.getLogger(CassiniExtension.class.getName());

    private String contextPath = "/";
    private String listener = ChappeListener.DEFAULT;

    @Override
    public String name() {
        return "rest-cassini";
    }

    @Override
    public int priority() {
        return 500;
    }

    @Override
    public void configure(VidocqConfiguration config) {
        this.contextPath = config.property("vidocq.rest.context-path", "/");
        this.listener = config.property("vidocq.rest.listener", ChappeListener.DEFAULT);
    }

    @Override
    public void beforeStart(VaubanContainerBuilder builder) {
    }

    @Override
    public void onStart(ExtensionContext context) {
        LOG.log(System.Logger.Level.INFO,
                "Cassini REST extension scaffolded (listener={0}, contextPath={1}) — M1 dispatcher pending",
                listener, contextPath);
    }

    @Override
    public void onStop() {
    }
}
