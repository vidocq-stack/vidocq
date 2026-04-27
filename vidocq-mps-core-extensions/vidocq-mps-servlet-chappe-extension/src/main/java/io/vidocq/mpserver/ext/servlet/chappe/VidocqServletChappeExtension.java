package io.vidocq.mpserver.ext.servlet.chappe;

import io.vidocq.mpserver.ext.chappe.ChappeListener;
import io.vidocq.mpserver.ext.chappe.ChappeMountPoint;
import io.vidocq.mpserver.ext.servlet.chappe.boot.WebAppDiscovery;
import io.vidocq.mpserver.ext.servlet.chappe.bridge.ChappeServletBridge;
import io.vidocq.mpserver.ext.servlet.chappe.container.VidocqServletContext;
import io.vidocq.mpserver.ext.servlet.chappe.dispatcher.FilterMapping;
import io.vidocq.mpserver.ext.servlet.chappe.dispatcher.FilterRegistry;
import io.vidocq.mpserver.ext.servlet.chappe.dispatcher.ServletDispatcher;
import io.vidocq.mpserver.ext.servlet.chappe.listener.ListenerRegistry;
import io.vidocq.mpserver.ext.servlet.chappe.session.InMemorySessionStore;
import io.vidocq.mpserver.ext.servlet.chappe.session.SessionManager;
import io.vidocq.mpserver.spi.ExtensionContext;
import io.vidocq.mpserver.spi.VidocqExtension;

import java.util.EventListener;
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
    private VidocqServletContext activeContext;
    private ListenerRegistry activeRegistry;

    @Override
    public String name() {
        return "servlet-chappe";
    }

    @Override
    public int priority() {
        return 500;
    }

    @Override
    public void configure(io.vidocq.mpserver.spi.VidocqConfiguration config) {
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
        List<EventListener> eventListeners =
                WebAppDiscovery.discoverListeners(context.beanManager());

        if (servletMappings.isEmpty() && filterMappings.isEmpty() && eventListeners.isEmpty()) {
            LOG.log(System.Logger.Level.INFO,
                    "No @WebServlet / @WebFilter / @WebListener beans discovered — servlet extension inactive");
            return;
        }

        ServletDispatcher dispatcher = new ServletDispatcher(servletMappings);
        FilterRegistry filterRegistry = new FilterRegistry(filterMappings);
        ListenerRegistry listeners = new ListenerRegistry();
        listeners.registerAll(eventListeners);

        VidocqServletContext servletContext = new VidocqServletContext(contextPath);
        servletContext.setListenerRegistry(listeners);
        SessionManager sessionManager = new SessionManager(
                new InMemorySessionStore(), servletContext, sessionTimeoutSeconds);
        sessionManager.setListenerRegistry(listeners);
        ChappeServletBridge bridge = new ChappeServletBridge(
                dispatcher, filterRegistry, servletContext, sessionManager, contextPath);

        String mountPrefix = "/".equals(contextPath) ? "" : contextPath;
        ChappeMountPoint.instance().mount(listener, mountPrefix, bridge);

        // Fire contextInitialized on all registered ServletContextListeners.
        listeners.fireContextInitialized(servletContext);
        this.activeContext = servletContext;
        this.activeRegistry = listeners;

        for (ServletDispatcher.Mapping m : servletMappings) {
            LOG.log(System.Logger.Level.INFO,
                    "Mapped servlet {0} -> {1}", m.servletName(), m.matcher().pattern());
        }
        for (FilterMapping m : filterMappings) {
            LOG.log(System.Logger.Level.INFO,
                    "Mapped filter {0} -> {1} [{2}]",
                    m.filterName(), m.matcher().pattern(), m.dispatcherTypes());
        }
        for (EventListener l : eventListeners) {
            LOG.log(System.Logger.Level.INFO, "Registered listener: {0}", l.getClass().getName());
        }
    }

    @Override
    public void onStop() {
        if (activeRegistry != null && activeContext != null) {
            activeRegistry.fireContextDestroyed(activeContext);
        }
    }
}
