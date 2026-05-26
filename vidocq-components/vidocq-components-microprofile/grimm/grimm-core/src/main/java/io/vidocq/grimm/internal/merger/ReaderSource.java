package io.vidocq.grimm.internal.merger;

import org.eclipse.microprofile.openapi.models.OpenAPI;

/**
 * Source produced by the configured {@code OASModelReader} (spec §4.1). Medium priority
 * in {@link ModelMerger} (above static file, below annotations).
 */
public final class ReaderSource implements ModelSource {

    private final OpenAPI model;

    public ReaderSource(OpenAPI model) {
        this.model = model;
    }

    @Override
    public OpenAPI getModel() {
        return model;
    }
}

