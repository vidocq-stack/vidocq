package io.vidocq.grimm.cdi;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.annotation.PostConstruct;
import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.models.OpenAPI;

/**
 * ServiceLoader bridge for CDI environments.
 *
 * <p>The core module provides an {@code OASFactoryResolver}. This bean eagerly triggers
 * MicroProfile OpenAPI factory discovery once at startup so CDI/Vauban deployments have
 * the resolver initialized before the model pipeline runs.</p>
 */
@ApplicationScoped
public class GrimmAutoDiscovery {

    @PostConstruct
    void initialize() {
        // Forces OASFactoryResolver service loading through OASFactory.
        OpenAPI model = OASFactory.createObject(OpenAPI.class);
        if (model.getOpenapi() == null) {
            model.setOpenapi("3.1.0");
        }
    }
}

