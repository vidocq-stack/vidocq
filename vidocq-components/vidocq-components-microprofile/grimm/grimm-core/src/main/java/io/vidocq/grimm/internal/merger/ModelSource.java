package io.vidocq.grimm.internal.merger;

import org.eclipse.microprofile.openapi.models.OpenAPI;

/**
 * Sealed interface for model sources in the merge pipeline.
 *
 * Spec §4.4: Three sources are merged in priority order:
 * annotations > OASModelReader > static file
 */
public sealed interface ModelSource permits
    StaticFileSource,
    AnnotationSource,
    ReaderSource {

    /**
     * Gets the OpenAPI model from this source.
     *
     * @return the OpenAPI model, or null if not present
     */
    OpenAPI getModel();
}

/**
 * Source for static file reading (lowest priority).
 * Spec §4.2: Static OpenAPI files.
 */
final class StaticFileSource implements ModelSource {
    private final OpenAPI model;

    public StaticFileSource(OpenAPI model) {
        this.model = model;
    }

    @Override
    public OpenAPI getModel() {
        return model;
    }
}

/**
 * Source for annotation scanning (medium priority).
 * Spec §3.3: Annotation scanning.
 */
final class AnnotationSource implements ModelSource {
    private final OpenAPI model;

    public AnnotationSource(OpenAPI model) {
        this.model = model;
    }

    @Override
    public OpenAPI getModel() {
        return model;
    }
}

/**
 * Source for OASModelReader (highest priority).
 * Spec §4.1: OASModelReader.
 */
final class ReaderSource implements ModelSource {
    private final OpenAPI model;

    public ReaderSource(OpenAPI model) {
        this.model = model;
    }

    @Override
    public OpenAPI getModel() {
        return model;
    }
}

