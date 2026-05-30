package io.vidocq.runtime.ext.rest.cassini;

import io.vidocq.cassini.cdi.vauban.VaubanBeanProvider;
import io.vidocq.cassini.chappe.ChappeHttpAdapter;
import io.vidocq.cassini.spi.bean.BeanProvider;
import io.vidocq.cassini.spi.http.CassiniStack;
import io.vidocq.chappe.api.Handler;
import io.vidocq.runtime.ext.chappe.spi.MountConfig;
import io.vidocq.runtime.ext.chappe.spi.MountHandlerProvider;
import io.vidocq.vauban.core.context.RequestContext;

/**
 * Provider {@code MountHandlerProvider} of type {@code restful} (Jakarta
 * RESTful Web Services) — Cassini implementation mounted on Chappe with a
 * {@link BeanProvider} Vauban from the Vidocq Runtime.
 *
 * <p>The {@code type} describes the <b>standard contract</b> (JAX-RS / Jakarta
 * RESTful Web Services), not the implementation. When another provider Jakarta
 * REST will be added (ex. Jersey, RESTEasy), it will also declare
 * {@code type() = "restful"}; the selection will then be made via the property
 * optional {@code vidocq.http.mount.<name>.impl=<id>} (to enter the
 * day when several implementations will co-exist).</p>
 *
 * <h3>Supported properties</h3>
 * <pre>{@code
 * vidocq.http.mount.<name>.type = restful
 * vidocq.http.mount.<name>.path = /api # default: / via the reader
 * # No other property: discovery of @Path/@Provider is done via Vauban.
 * }</pre>
 */
public final class CassiniMountHandlerProvider implements MountHandlerProvider {

    @Override
    public String type() {
        return "restful";
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
