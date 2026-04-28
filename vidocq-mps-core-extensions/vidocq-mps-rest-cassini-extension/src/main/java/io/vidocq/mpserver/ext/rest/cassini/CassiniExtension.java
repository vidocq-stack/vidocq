package io.vidocq.mpserver.ext.rest.cassini;

import io.vidocq.cassini.chappe.ChappeHttpAdapter;
import io.vidocq.cassini.internal.ExceptionMapperRegistry;
import io.vidocq.cassini.internal.Invoker;
import io.vidocq.cassini.internal.MessageBodyRegistry;
import io.vidocq.cassini.internal.ResourceMethod;
import io.vidocq.cassini.internal.ResourceScanner;
import io.vidocq.cassini.internal.UriRouter;
import io.vidocq.mpserver.ext.chappe.ChappeListener;
import io.vidocq.mpserver.ext.chappe.ChappeMountPoint;
import io.vidocq.mpserver.spi.ExtensionContext;
import io.vidocq.mpserver.spi.VidocqConfiguration;
import io.vidocq.mpserver.spi.VidocqExtension;
import io.vidocq.vauban.core.container.VaubanContainerBuilder;
import io.vidocq.vauban.core.context.RequestContext;

import java.util.List;

/**
 * Extension Vidocq-MPS qui branche {@link io.vidocq.cassini} (Jakarta REST 4.0
 * standalone) sur le moteur HTTP Chappe via {@link ChappeHttpAdapter}.
 *
 * <p>Priorité 500 : tourne après {@code ChappeEngineExtension} et avant
 * {@code ChappeServerBootstrap}, afin de contribuer un handler JAX-RS au
 * {@link ChappeMountPoint}.
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
        List<ResourceMethod> routes = ResourceScanner.discover(context.beanManager());
        if (routes.isEmpty()) {
            LOG.log(System.Logger.Level.INFO,
                    "No @Path beans discovered — Cassini REST extension inactive");
            return;
        }

        UriRouter router = new UriRouter(routes);
        Invoker invoker = Invoker.forBeanManager(context.beanManager());

        // Activation @RequestScoped via Vauban autour de chaque dispatch.
        RequestContext requestContext = new RequestContext();
        ChappeHttpAdapter.Scoped scoped = requestContext::runInScope;
        ChappeHttpAdapter bridge = new ChappeHttpAdapter(router, invoker, scoped);

        String mountPrefix = "/".equals(contextPath) ? "" : contextPath;
        ChappeMountPoint.instance().mount(listener, mountPrefix, bridge);

        for (ResourceMethod r : routes) {
            LOG.log(System.Logger.Level.INFO,
                    "  Endpoint {0} {1}{2} -> {3}.{4}",
                    r.httpMethod(), contextPath, r.path(),
                    r.beanClass().getSimpleName(), r.javaMethod().getName());
        }
        LOG.log(System.Logger.Level.INFO,
                "Cassini REST extension mounted on listener={0} prefix={1} ({2} endpoint(s))",
                listener, mountPrefix.isEmpty() ? "/" : mountPrefix, routes.size());
    }

    @Override
    public void onStop() {
    }
}
