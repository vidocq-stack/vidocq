package io.vidocq.mpserver.ext.rest.cassini;

import io.vidocq.cassini.cdi.vauban.VaubanBeanProvider;
import io.vidocq.cassini.chappe.ChappeHttpAdapter;
import io.vidocq.cassini.spi.bean.BeanProvider;
import io.vidocq.cassini.spi.http.CassiniStack;
import io.vidocq.chappe.api.Handler;
import io.vidocq.mpserver.ext.chappe.spi.MountConfig;
import io.vidocq.mpserver.ext.chappe.spi.MountHandlerProvider;
import io.vidocq.vauban.core.context.RequestContext;

/**
 * Provider {@code MountHandlerProvider} de type {@code cassini} : monte un
 * runtime Jakarta REST 4.0 (Cassini) sur Chappe avec le {@link BeanProvider}
 * Vauban du runtime Vidocq-MPS.
 *
 * <h3>Properties supportées</h3>
 * <pre>{@code
 * vidocq.mount.<name>.type = cassini
 * vidocq.mount.<name>.path = /api      # défaut : / via le reader
 * # Aucune autre property : la découverte des @Path/@Provider se fait via Vauban.
 * }</pre>
 */
public final class CassiniMountHandlerProvider implements MountHandlerProvider {

    @Override
    public String type() {
        return "cassini";
    }

    @Override
    public Handler create(MountConfig cfg) {
        BeanProvider beanProvider = new VaubanBeanProvider(cfg.extensionContext().container());
        CassiniStack stack = CassiniStack.builder()
                .beanProvider(beanProvider)
                .build();
        RequestContext requestContext = new RequestContext();
        return new ChappeHttpAdapter(stack.adapter(), requestContext::runInScope);
    }
}
