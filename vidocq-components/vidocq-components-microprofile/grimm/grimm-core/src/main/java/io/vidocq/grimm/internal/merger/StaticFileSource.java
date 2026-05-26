package io.vidocq.grimm.internal.merger;

import org.eclipse.microprofile.openapi.models.OpenAPI;

/**
 * Source coming from the static {@code META-INF/openapi.*} file (spec §4.2).
 * Lowest priority in {@link ModelMerger}.
 */
public final class StaticFileSource implements ModelSource {

    private final OpenAPI model;

    public StaticFileSource(OpenAPI model) {
        this.model = model;
    }

    @Override
    public OpenAPI getModel() {
        return model;
    }
}

