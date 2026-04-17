package fr.vidocq.vidocq.ext.servlet.chappe;

import fr.vidocq.vidocq.ext.chappe.ChappeListener;
import fr.vidocq.vidocq.ext.chappe.ChappeMountPoint;
import fr.vidocq.vidocq.ext.servlet.chappe.boot.WebAppDiscovery;
import fr.vidocq.vidocq.ext.servlet.chappe.bridge.ChappeServletBridge;
import fr.vidocq.vidocq.ext.servlet.chappe.container.VidocqServletContext;
import fr.vidocq.vidocq.ext.servlet.chappe.dispatcher.ServletDispatcher;
import fr.vidocq.vidocq.spi.ExtensionContext;
import fr.vidocq.vidocq.spi.VidocqExtension;

import java.util.List;

/**
 * Extension Vidocq implémentant Jakarta Servlet 6.1 sur le moteur Chappe.
 *
 * <p>Priorité 500 : tourne après {@code ChappeEngineExtension} (100) et avant
 * {@code ChappeServerBootstrap} (10 000), afin de contribuer un handler
 * servlet au {@link ChappeMountPoint}.</p>
 *
 * <h3>Configuration</h3>
 * <ul>
 *   <li>{@code vidocq.servlet.context-path} — préfixe de montage (défaut : {@code /})</li>
 *   <li>{@code vidocq.servlet.listener} — listener Chappe cible (défaut : {@link ChappeListener#DEFAULT})</li>
 * </ul>
 */
public final class VidocqServletChappeExtension implements VidocqExtension {

    private static final System.Logger LOG =
            System.getLogger(VidocqServletChappeExtension.class.getName());

    private String contextPath = "/";
    private String listener = ChappeListener.DEFAULT;

    @Override
    public String name() {
        return "servlet-chappe";
    }

    @Override
    public int priority() {
        return 500;
    }

    @Override
    public void configure(fr.vidocq.vidocq.spi.VidocqConfiguration config) {
        this.contextPath = config.property("vidocq.servlet.context-path", "/");
        this.listener = config.property("vidocq.servlet.listener", ChappeListener.DEFAULT);
    }

    @Override
    public void onStart(ExtensionContext context) {
        List<ServletDispatcher.Mapping> mappings =
                WebAppDiscovery.discover(context.beanManager());

        if (mappings.isEmpty()) {
            LOG.log(System.Logger.Level.INFO,
                    "No @WebServlet beans discovered — servlet extension inactive");
            return;
        }

        ServletDispatcher dispatcher = new ServletDispatcher(mappings);
        VidocqServletContext servletContext = new VidocqServletContext(contextPath);
        ChappeServletBridge bridge = new ChappeServletBridge(dispatcher, servletContext, contextPath);

        String mountPrefix = "/".equals(contextPath) ? "" : contextPath;
        ChappeMountPoint.instance().mount(listener, mountPrefix.isEmpty() ? "/" : mountPrefix, bridge);

        for (ServletDispatcher.Mapping m : mappings) {
            LOG.log(System.Logger.Level.INFO,
                    "Mapped servlet {0} -> {1}", m.servletName(), m.matcher().pattern());
        }
    }
}
