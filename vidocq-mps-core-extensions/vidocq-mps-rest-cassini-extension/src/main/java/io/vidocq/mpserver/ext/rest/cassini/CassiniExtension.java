package io.vidocq.mpserver.ext.rest.cassini;

import io.vidocq.cassini.cdi.vauban.VaubanBeanProvider;
import io.vidocq.cassini.chappe.ChappeHttpAdapter;
import io.vidocq.cassini.spi.bean.BeanProvider;
import io.vidocq.cassini.spi.http.CassiniStack;
import io.vidocq.mpserver.ext.chappe.ChappeListener;
import io.vidocq.mpserver.ext.chappe.ChappeMountPoint;
import io.vidocq.mpserver.spi.ExtensionContext;
import io.vidocq.mpserver.spi.VidocqConfiguration;
import io.vidocq.mpserver.spi.VidocqExtension;
import io.vidocq.vauban.core.container.VaubanContainerBuilder;
import io.vidocq.vauban.core.context.RequestContext;

import java.util.Set;

/**
 * Extension Vidocq-MPS qui branche Cassini (Jakarta REST 4.0 standalone) sur le
 * moteur HTTP Chappe via {@link ChappeHttpAdapter}.
 *
 * <p>Priorité 500 : tourne après {@code ChappeEngineExtension} et avant
 * {@code ChappeServerBootstrap}, afin de contribuer un handler JAX-RS au
 * {@link ChappeMountPoint}.
 *
 * <p>Le bootstrap passe par la SPI publique {@link CassiniStack#builder()} :
 * cassini-core fournit le {@code BuilderFactory} via ServiceLoader, et un
 * {@link VaubanBeanProvider} construit sur le {@code VaubanContainer} du
 * runtime expose les ressources {@code @Path}/{@code @Provider} découvertes
 * par CDI.
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
        BeanProvider beanProvider = new VaubanBeanProvider(context.container());
        Set<Class<?>> resourceClasses = beanProvider.getResourceClasses();
        if (resourceClasses.isEmpty()) {
            LOG.log(System.Logger.Level.INFO,
                    "No @Path beans discovered — Cassini REST extension inactive");
            return;
        }

        CassiniStack stack = CassiniStack.builder()
                .beanProvider(beanProvider)
                .build();

        // Activation @RequestScoped via Vauban autour de chaque dispatch.
        RequestContext requestContext = new RequestContext();
        ChappeHttpAdapter.Scoped scoped = requestContext::runInScope;
        ChappeHttpAdapter bridge = new ChappeHttpAdapter(stack.adapter(), scoped);

        String mountPrefix = "/".equals(contextPath) ? "" : contextPath;
        ChappeMountPoint.instance().mount(listener, mountPrefix, bridge);

        LOG.log(System.Logger.Level.INFO,
                "Cassini REST extension mounted on listener={0} prefix={1} ({2} resource class(es))",
                listener, mountPrefix.isEmpty() ? "/" : mountPrefix, resourceClasses.size());
    }

    @Override
    public void onStop() {
    }
}
