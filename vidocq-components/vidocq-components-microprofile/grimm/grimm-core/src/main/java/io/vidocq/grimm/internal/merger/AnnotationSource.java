package io.vidocq.grimm.internal.merger;

import org.eclipse.microprofile.openapi.models.OpenAPI;

/**
 * Source produced by annotation scanning (spec §3). Highest priority in {@link ModelMerger}.
 */
public final class AnnotationSource implements ModelSource {

    private final OpenAPI model;

    public AnnotationSource(OpenAPI model) {
        this.model = model;
    }

    @Override
    public OpenAPI getModel() {
        return model;
    }
}

