package io.vidocq.grimm.internal.merger;

import org.eclipse.microprofile.openapi.models.OpenAPI;

/**
 * Sealed hierarchy of model sources fed to {@link ModelMerger} (spec §4.4).
 *
 * <p>Priority order (high → low): annotations &gt; {@code OASModelReader} &gt; static file.
 * Each concrete subtype carries the {@link OpenAPI} model produced by one of the three
 * pipeline stages.</p>
 */
public sealed interface ModelSource
        permits StaticFileSource, AnnotationSource, ReaderSource {

    /** @return the OpenAPI model contributed by this source, or {@code null}. */
    OpenAPI getModel();
}



