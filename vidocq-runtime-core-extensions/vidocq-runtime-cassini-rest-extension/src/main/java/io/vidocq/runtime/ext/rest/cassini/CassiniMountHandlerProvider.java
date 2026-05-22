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
 * Provider {@code MountHandlerProvider} de type {@code restful} (Jakarta
 * RESTful Web Services) — implémentation Cassini montée sur Chappe avec un
 * {@link BeanProvider} Vauban issu du runtime Vidocq Runtime.
 *
 * <p>Le {@code type} décrit le <b>contrat standard</b> (JAX-RS / Jakarta
 * RESTful Web Services), pas l'implémentation. Quand un autre provider Jakarta
 * REST sera ajouté (ex. Jersey, RESTEasy), il déclarera également
 * {@code type() = "restful"} ; la sélection se fera alors via la property
 * optionnelle {@code vidocq.http.mount.<name>.impl=<id>} (à introduire le
 * jour où plusieurs implémentations co-existeront).</p>
 *
 * <h3>Properties supportées</h3>
 * <pre>{@code
 * vidocq.http.mount.<name>.type = restful
 * vidocq.http.mount.<name>.path = /api      # défaut : / via le reader
 * # Aucune autre property : la découverte des @Path/@Provider se fait via Vauban.
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
