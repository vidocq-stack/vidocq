package fr.vidocq.vidocq.ext.servlet.chappe;

import fr.vidocq.vidocq.ext.chappe.ChappeListener;
import fr.vidocq.vidocq.ext.chappe.ChappeMountPoint;
import fr.vidocq.vidocq.ext.servlet.chappe.boot.WebAppDiscovery;
import fr.vidocq.vidocq.ext.servlet.chappe.bridge.ChappeServletBridge;
import fr.vidocq.vidocq.ext.servlet.chappe.container.VidocqServletContext;
import fr.vidocq.vidocq.ext.servlet.chappe.dispatcher.FilterMapping;
import fr.vidocq.vidocq.ext.servlet.chappe.dispatcher.FilterRegistry;
import fr.vidocq.vidocq.ext.servlet.chappe.dispatcher.ServletDispatcher;
import fr.vidocq.vidocq.ext.servlet.chappe.session.InMemorySessionStore;
import fr.vidocq.vidocq.ext.servlet.chappe.session.SessionManager;
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
    private int sessionTimeoutSeconds = 30 * 60;

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
        this.sessionTimeoutSeconds = Integer.parseInt(
                config.property("vidocq.servlet.session.timeout-seconds", "1800"));
    }

    @Override
    public void onStart(ExtensionContext context) {
        List<ServletDispatcher.Mapping> servletMappings =
                WebAppDiscovery.discoverServlets(context.beanManager());
        List<FilterMapping> filterMappings =
                WebAppDiscovery.discoverFilters(context.beanManager());

        if (servletMappings.isEmpty() && filterMappings.isEmpty()) {
            LOG.log(System.Logger.Level.INFO,
                    "No @WebServlet / @WebFilter beans discovered — servlet extension inactive");
            return;
        }

        ServletDispatcher dispatcher = new ServletDispatcher(servletMappings);
        FilterRegistry filterRegistry = new FilterRegistry(filterMappings);
        VidocqServletContext servletContext = new VidocqServletContext(contextPath);
        SessionManager sessionManager = new SessionManager(
                new InMemorySessionStore(), servletContext, sessionTimeoutSeconds);
        ChappeServletBridge bridge = new ChappeServletBridge(
                dispatcher, filterRegistry, servletContext, sessionManager, contextPath);

        String mountPrefix = "/".equals(contextPath) ? "" : contextPath;
        ChappeMountPoint.instance().mount(listener, mountPrefix.isEmpty() ? "/" : mountPrefix, bridge);

        for (ServletDispatcher.Mapping m : servletMappings) {
            LOG.log(System.Logger.Level.INFO,
                    "Mapped servlet {0} -> {1}", m.servletName(), m.matcher().pattern());
        }
        for (FilterMapping m : filterMappings) {
            LOG.log(System.Logger.Level.INFO,
                    "Mapped filter {0} -> {1} [{2}]",
                    m.filterName(), m.matcher().pattern(), m.dispatcherTypes());
        }
    }
}
