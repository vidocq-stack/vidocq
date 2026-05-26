package io.vidocq.grimm.internal.merger;

import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.models.Components;
import org.eclipse.microprofile.openapi.models.ExternalDocumentation;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.Paths;
import org.eclipse.microprofile.openapi.models.info.Info;
import org.eclipse.microprofile.openapi.models.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.models.servers.Server;
import org.eclipse.microprofile.openapi.models.tags.Tag;

import java.util.List;
import java.util.Objects;

/**
 * Merges multiple {@link ModelSource} instances according to spec priority.
 *
 * Spec §4.4: Priority order is annotations > OASModelReader > static file.
 *
 * This merger uses pattern matching on the sealed {@link ModelSource} interface.
 */
public final class ModelMerger {

    /**
     * Merges the given sources in priority order.
     *
     * @param sources the sources to merge (should include StaticFileSource, AnnotationSource,
     *                ReaderSource in any order)
     * @return the merged OpenAPI model
     */
    public OpenAPI merge(List<ModelSource> sources) {
        Objects.requireNonNull(sources, "sources must not be null");

        // Extract sources by type
        OpenAPI staticModel = null;
        OpenAPI readerModel = null;
        OpenAPI annotationModel = null;

        for (ModelSource source : sources) {
            if (source instanceof StaticFileSource staticSource) {
                staticModel = staticSource.getModel();
            } else if (source instanceof ReaderSource readerSource) {
                readerModel = readerSource.getModel();
            } else if (source instanceof AnnotationSource annotationSource) {
                annotationModel = annotationSource.getModel();
            }
        }

        // Start with an empty model
        OpenAPI result = OASFactory.createObject(OpenAPI.class);

        // Priority order per spec: static file → reader → annotations
        if (staticModel != null) {
            mergeStaticFile(result, staticModel);
        }
        if (readerModel != null) {
            mergeReader(result, readerModel);
        }
        if (annotationModel != null) {
            mergeAnnotations(result, annotationModel);
        }

        return result;
    }

    /**
     * Merges a static file model (lowest priority, baseline).
     */
    private void mergeStaticFile(OpenAPI target, OpenAPI source) {
        // Set fields from static file only if target doesn't already have them
        if (target.getOpenapi() == null && source.getOpenapi() != null) {
            target.setOpenapi(source.getOpenapi());
        }
        if (target.getInfo() == null && source.getInfo() != null) {
            target.setInfo(source.getInfo());
        }
        if (target.getPaths() == null && source.getPaths() != null) {
            target.setPaths(source.getPaths());
        }
        if (target.getComponents() == null && source.getComponents() != null) {
            target.setComponents(source.getComponents());
        }
        if (target.getServers() == null && source.getServers() != null) {
            target.setServers(source.getServers());
        }
        if (target.getSecurity() == null && source.getSecurity() != null) {
            target.setSecurity(source.getSecurity());
        }
        if (target.getTags() == null && source.getTags() != null) {
            target.setTags(source.getTags());
        }
    }

    /**
     * Merges a reader model (medium priority, overrides static file).
     */
    private void mergeReader(OpenAPI target, OpenAPI source) {
        if (source.getOpenapi() != null) {
            target.setOpenapi(source.getOpenapi());
        }
        if (source.getInfo() != null) {
            target.setInfo(source.getInfo());
        }
        if (source.getPaths() != null) {
            if (target.getPaths() == null) {
                target.setPaths(source.getPaths());
            } else {
                mergePaths(target.getPaths(), source.getPaths());
            }
        }
        if (source.getComponents() != null) {
            target.setComponents(source.getComponents());
        }
        if (source.getServers() != null && !source.getServers().isEmpty()) {
            target.setServers(source.getServers());
        }
        if (source.getSecurity() != null && !source.getSecurity().isEmpty()) {
            target.setSecurity(source.getSecurity());
        }
        if (source.getTags() != null && !source.getTags().isEmpty()) {
            target.setTags(source.getTags());
        }
    }

    /**
     * Merges annotation model (highest priority, overrides everything).
     */
    private void mergeAnnotations(OpenAPI target, OpenAPI source) {
        if (source.getOpenapi() != null) {
            target.setOpenapi(source.getOpenapi());
        }
        if (source.getInfo() != null) {
            target.setInfo(source.getInfo());
        }
        if (source.getPaths() != null) {
            if (target.getPaths() == null) {
                target.setPaths(source.getPaths());
            } else {
                mergePaths(target.getPaths(), source.getPaths());
            }
        }
        if (source.getComponents() != null) {
            target.setComponents(source.getComponents());
        }
        if (source.getServers() != null && !source.getServers().isEmpty()) {
            target.setServers(source.getServers());
        }
        if (source.getSecurity() != null && !source.getSecurity().isEmpty()) {
            target.setSecurity(source.getSecurity());
        }
        if (source.getTags() != null && !source.getTags().isEmpty()) {
            target.setTags(source.getTags());
        }
    }

    private void mergePaths(Paths target, Paths source) {
        if (source.getPathItems() != null) {
            for (String path : source.getPathItems().keySet()) {
                if (!target.hasPathItem(path)) {
                    target.addPathItem(path, source.getPathItem(path));
                }
            }
        }
    }
}





