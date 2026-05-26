package io.vidocq.grimm.internal.scanner;

import io.vidocq.grimm.internal.config.ScanConfig;
import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.annotations.ExternalDocumentation;
import org.eclipse.microprofile.openapi.annotations.OpenAPIDefinition;
import org.eclipse.microprofile.openapi.annotations.info.Info;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.servers.Server;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.eclipse.microprofile.openapi.models.OpenAPI;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
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
    private final JaxRsResourceScanner jaxRsScanner = new JaxRsResourceScanner();

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
        Objects.requireNonNull(classes, "classes must not be null");
        OpenAPI openAPI = OASFactory.createObject(OpenAPI.class);

        for (Class<?> clazz : classes) {
            if (!config.shouldScan(clazz.getName())) {
                continue;
            }

            processOpenAPIDefinition(clazz, openAPI);
            processTags(clazz, openAPI);
            processServers(clazz, openAPI);
            processExternalDocumentation(clazz, openAPI);
            jaxRsScanner.scan(clazz, openAPI);
        }

        return openAPI;
    }

    private void processOpenAPIDefinition(Class<?> clazz, OpenAPI openAPI) {
        OpenAPIDefinition annotation = clazz.getAnnotation(OpenAPIDefinition.class);
        if (annotation == null) {
            return;
        }

        if (annotation.info() != null && !annotation.info().title().isEmpty()) {
            org.eclipse.microprofile.openapi.models.info.Info info = OASFactory.createObject(
                org.eclipse.microprofile.openapi.models.info.Info.class
            );
            applyInfoAnnotation(annotation.info(), info);
            openAPI.setInfo(info);
        }

        addTags(openAPI, annotation.tags());
        addServers(openAPI, annotation.servers());
        addSecurityRequirements(openAPI, annotation.security());

        ExternalDocumentation externalDocs = annotation.externalDocs();
        if (!externalDocs.url().isEmpty()) {
            openAPI.setExternalDocs(toModelExternalDocs(externalDocs));
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
        addTags(openAPI, clazz.getAnnotationsByType(Tag.class));
        for (Method method : clazz.getDeclaredMethods()) {
            addTags(openAPI, method.getAnnotationsByType(Tag.class));
        }
    }

    private void processServers(Class<?> clazz, OpenAPI openAPI) {
        addServers(openAPI, clazz.getAnnotationsByType(Server.class));
        for (Method method : clazz.getDeclaredMethods()) {
            addServers(openAPI, method.getAnnotationsByType(Server.class));
        }
    }

    private void processExternalDocumentation(Class<?> clazz, OpenAPI openAPI) {
        OpenAPIDefinition openApiDefinition = clazz.getAnnotation(OpenAPIDefinition.class);
        if (openApiDefinition != null && !openApiDefinition.externalDocs().url().isEmpty()) {
            return;
        }

        ExternalDocumentation annotation = clazz.getAnnotation(ExternalDocumentation.class);
        if (annotation != null && !annotation.url().isEmpty()) {
            openAPI.setExternalDocs(toModelExternalDocs(annotation));
        }
    }

    private org.eclipse.microprofile.openapi.models.ExternalDocumentation toModelExternalDocs(
        ExternalDocumentation annotation
    ) {
        org.eclipse.microprofile.openapi.models.ExternalDocumentation externalDocs = OASFactory.createObject(
            org.eclipse.microprofile.openapi.models.ExternalDocumentation.class
        );
        externalDocs.setUrl(annotation.url());
        if (!annotation.description().isEmpty()) {
            externalDocs.setDescription(annotation.description());
        }
        return externalDocs;
    }

    private void addTags(OpenAPI openAPI, Tag[] tagAnnotations) {
        for (Tag tagAnnotation : tagAnnotations) {
            String name = tagAnnotation.name();
            if (name.isEmpty() || containsTag(openAPI, name)) {
                continue;
            }
            org.eclipse.microprofile.openapi.models.tags.Tag tag = OASFactory.createObject(
                org.eclipse.microprofile.openapi.models.tags.Tag.class
            );
            tag.setName(name);
            if (!tagAnnotation.description().isEmpty()) {
                tag.setDescription(tagAnnotation.description());
            }
            openAPI.addTag(tag);
        }
    }

    private boolean containsTag(OpenAPI openAPI, String name) {
        List<org.eclipse.microprofile.openapi.models.tags.Tag> tags = openAPI.getTags();
        if (tags == null) {
            return false;
        }
        return tags.stream().anyMatch(tag -> name.equals(tag.getName()));
    }

    private void addServers(OpenAPI openAPI, Server[] serverAnnotations) {
        for (Server serverAnnotation : serverAnnotations) {
            String url = serverAnnotation.url();
            if (url.isEmpty() || containsServer(openAPI, url)) {
                continue;
            }
            org.eclipse.microprofile.openapi.models.servers.Server server = OASFactory.createObject(
                org.eclipse.microprofile.openapi.models.servers.Server.class
            );
            server.setUrl(url);
            if (!serverAnnotation.description().isEmpty()) {
                server.setDescription(serverAnnotation.description());
            }
            openAPI.addServer(server);
        }
    }

    private boolean containsServer(OpenAPI openAPI, String url) {
        List<org.eclipse.microprofile.openapi.models.servers.Server> servers = openAPI.getServers();
        if (servers == null) {
            return false;
        }
        return servers.stream().anyMatch(server -> url.equals(server.getUrl()));
    }

    private void addSecurityRequirements(OpenAPI openAPI, SecurityRequirement[] securityAnnotations) {
        for (SecurityRequirement securityAnnotation : securityAnnotations) {
            if (securityAnnotation.name().isEmpty()) {
                continue;
            }
            org.eclipse.microprofile.openapi.models.security.SecurityRequirement requirement = OASFactory.createObject(
                org.eclipse.microprofile.openapi.models.security.SecurityRequirement.class
            );
            requirement.addScheme(securityAnnotation.name(), List.of(securityAnnotation.scopes()));
            openAPI.addSecurityRequirement(requirement);
        }
    }
}
