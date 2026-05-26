package io.vidocq.grimm.internal.scanner;

import io.vidocq.grimm.internal.config.ScanConfig;
import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.annotations.OpenAPIDefinition;
import org.eclipse.microprofile.openapi.annotations.info.Info;
import org.eclipse.microprofile.openapi.annotations.servers.Server;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.eclipse.microprofile.openapi.models.OpenAPI;

import java.util.Collection;
import java.util.Objects;

/**
 * Scans MP OpenAPI annotations on JAX-RS resource classes and produces a partial OpenAPI model.
 *
 * Spec §3.3: Annotation scanning
 * Spec §3.4: @OpenAPIDefinition
 * Spec §3.5: @Tag and @Server
 */
public final class AnnotationScanner {

    private final ScanConfig config;

    /**
     * Creates an {@code AnnotationScanner} with the given configuration.
     *
     * @param config the scanning configuration
     */
    public AnnotationScanner(ScanConfig config) {
        this.config = Objects.requireNonNull(config, "config must not be null");
    }

    /**
     * Scans the given classes for OpenAPI annotations and produces a partial model.
     *
     * Spec §3.4: Document-level annotations are processed in priority order.
     *
     * @param classes the classes to scan
     * @return a partial {@link OpenAPI} model with annotations from the classes
     */
    public OpenAPI scanClasses(Collection<Class<?>> classes) {
        OpenAPI openAPI = OASFactory.createObject(OpenAPI.class);

        // Process each class in the collection
        for (Class<?> clazz : classes) {
            if (!config.shouldScan(clazz.getName())) {
                continue;
            }

            processOpenAPIDefinition(clazz, openAPI);
            processTags(clazz, openAPI);
            processServers(clazz, openAPI);
            processExternalDocumentation(clazz, openAPI);
        }

        return openAPI;
    }

    private void processOpenAPIDefinition(Class<?> clazz, OpenAPI openAPI) {
        // Spec §3.4: @OpenAPIDefinition at document level
        OpenAPIDefinition annotation = clazz.getAnnotation(OpenAPIDefinition.class);
        if (annotation == null) {
            return;
        }

        // Process info
        if (annotation.info() != null && !annotation.info().title().isEmpty()) {
            org.eclipse.microprofile.openapi.models.info.Info info = OASFactory.createObject(
                org.eclipse.microprofile.openapi.models.info.Info.class
            );
            applyInfoAnnotation(annotation.info(), info);
            openAPI.setInfo(info);
        }
    }

    private void applyInfoAnnotation(Info infoAnnotation, org.eclipse.microprofile.openapi.models.info.Info info) {
        if (!infoAnnotation.title().isEmpty()) {
            info.setTitle(infoAnnotation.title());
        }
        if (!infoAnnotation.version().isEmpty()) {
            info.setVersion(infoAnnotation.version());
        }
        if (!infoAnnotation.description().isEmpty()) {
            info.setDescription(infoAnnotation.description());
        }
        if (!infoAnnotation.summary().isEmpty()) {
            info.setSummary(infoAnnotation.summary());
        }
        if (!infoAnnotation.termsOfService().isEmpty()) {
            info.setTermsOfService(infoAnnotation.termsOfService());
        }
    }

    private void processTags(Class<?> clazz, OpenAPI openAPI) {
        // Spec §3.5: @Tag and @Tags annotations
        Tag[] tags = clazz.getAnnotationsByType(Tag.class);
        for (Tag tagAnnotation : tags) {
            org.eclipse.microprofile.openapi.models.tags.Tag tag = OASFactory.createObject(
                org.eclipse.microprofile.openapi.models.tags.Tag.class
            );
            if (!tagAnnotation.name().isEmpty()) {
                tag.setName(tagAnnotation.name());
            }
            if (!tagAnnotation.description().isEmpty()) {
                tag.setDescription(tagAnnotation.description());
            }
            openAPI.addTag(tag);
        }
    }

    private void processServers(Class<?> clazz, OpenAPI openAPI) {
        // Spec §3.5: @Server and @Servers annotations
        Server[] servers = clazz.getAnnotationsByType(Server.class);
        for (Server serverAnnotation : servers) {
            org.eclipse.microprofile.openapi.models.servers.Server server = OASFactory.createObject(
                org.eclipse.microprofile.openapi.models.servers.Server.class
            );
            if (!serverAnnotation.url().isEmpty()) {
                server.setUrl(serverAnnotation.url());
            }
            if (!serverAnnotation.description().isEmpty()) {
                server.setDescription(serverAnnotation.description());
            }
            openAPI.addServer(server);
        }
    }

    private void processExternalDocumentation(Class<?> clazz, OpenAPI openAPI) {
        // Spec §3.4: @ExternalDocumentation can be present on document level
        // (Implementation deferred to later phase if needed)
    }
}

