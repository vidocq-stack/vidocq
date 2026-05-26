package io.vidocq.grimm.internal.scanner;

import io.vidocq.grimm.internal.config.ScanConfig;
import io.vidocq.grimm.internal.schema.SchemaGenerator;
import io.vidocq.grimm.internal.schema.SchemaRegistry;
import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.annotations.Components;
import org.eclipse.microprofile.openapi.annotations.ExternalDocumentation;
import org.eclipse.microprofile.openapi.annotations.OpenAPIDefinition;
import org.eclipse.microprofile.openapi.annotations.headers.Header;
import org.eclipse.microprofile.openapi.annotations.info.Contact;
import org.eclipse.microprofile.openapi.annotations.info.Info;
import org.eclipse.microprofile.openapi.annotations.info.License;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.servers.Server;
import org.eclipse.microprofile.openapi.annotations.servers.ServerVariable;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.Paths;

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
    private final JaxRsResourceScanner jaxRsScanner;
    private final SchemaRegistry schemaRegistry;
    private final SchemaGenerator schemaGenerator;

    /**
     * Creates an {@code AnnotationScanner} with the given configuration.
     *
     * @param config the scanning configuration
     */
    public AnnotationScanner(ScanConfig config) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.schemaRegistry = new SchemaRegistry();
        this.schemaGenerator = new SchemaGenerator(schemaRegistry);
        this.jaxRsScanner = new JaxRsResourceScanner(schemaGenerator);
    }

    /** Returns the per-scan {@link SchemaRegistry} (exposed for testing / inspection). */
    public SchemaRegistry schemaRegistry() {
        return schemaRegistry;
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
            processExternalDocumentation(clazz, openAPI);
            jaxRsScanner.scan(clazz, openAPI);
        }

        // Spec §3.10 — interned schemas (POJOs, enums) become components/schemas.
        schemaRegistry.applyTo(openAPI);

        if (openAPI.getPaths() == null) {
            openAPI.setPaths(OASFactory.createObject(Paths.class));
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
        addComponents(openAPI, annotation.components());

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

        Contact contactAnnotation = infoAnnotation.contact();
        if (!contactAnnotation.name().isEmpty()
            || !contactAnnotation.url().isEmpty()
            || !contactAnnotation.email().isEmpty()) {
            org.eclipse.microprofile.openapi.models.info.Contact contact = OASFactory.createObject(
                org.eclipse.microprofile.openapi.models.info.Contact.class
            );
            if (!contactAnnotation.name().isEmpty()) {
                contact.setName(contactAnnotation.name());
            }
            if (!contactAnnotation.url().isEmpty()) {
                contact.setUrl(contactAnnotation.url());
            }
            if (!contactAnnotation.email().isEmpty()) {
                contact.setEmail(contactAnnotation.email());
            }
            info.setContact(contact);
        }

        License licenseAnnotation = infoAnnotation.license();
        if (!licenseAnnotation.name().isEmpty()
            || !licenseAnnotation.url().isEmpty()
            || !licenseAnnotation.identifier().isEmpty()) {
            org.eclipse.microprofile.openapi.models.info.License license = OASFactory.createObject(
                org.eclipse.microprofile.openapi.models.info.License.class
            );
            if (!licenseAnnotation.name().isEmpty()) {
                license.setName(licenseAnnotation.name());
            }
            if (!licenseAnnotation.url().isEmpty()) {
                license.setUrl(licenseAnnotation.url());
            }
            if (!licenseAnnotation.identifier().isEmpty()) {
                license.setIdentifier(licenseAnnotation.identifier());
            }
            info.setLicense(license);
        }
    }

    private void processTags(Class<?> clazz, OpenAPI openAPI) {
        addTags(openAPI, clazz.getAnnotationsByType(Tag.class));
        for (Method method : clazz.getDeclaredMethods()) {
            addTags(openAPI, method.getAnnotationsByType(Tag.class));
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
            for (ServerVariable variableAnnotation : serverAnnotation.variables()) {
                String variableName = variableAnnotation.name();
                if (variableName.isEmpty()) {
                    continue;
                }
                var variable = OASFactory.createObject(org.eclipse.microprofile.openapi.models.servers.ServerVariable.class);
                if (!variableAnnotation.description().isEmpty()) {
                    variable.setDescription(variableAnnotation.description());
                }
                if (!variableAnnotation.defaultValue().isEmpty()) {
                    variable.setDefaultValue(variableAnnotation.defaultValue());
                }
                if (variableAnnotation.enumeration().length > 0) {
                    variable.setEnumeration(List.of(variableAnnotation.enumeration()));
                }
                server.addVariable(variableName, variable);
            }
            openAPI.addServer(server);
        }
    }

    private void addComponents(OpenAPI openAPI, Components componentsAnnotation) {
        if (componentsAnnotation == null) {
            return;
        }
        addComponentHeaders(openAPI, componentsAnnotation.headers());
    }

    private void addComponentHeaders(OpenAPI openAPI, Header[] headers) {
        if (headers == null || headers.length == 0) {
            return;
        }
        org.eclipse.microprofile.openapi.models.Components components = openAPI.getComponents();
        if (components == null) {
            components = OASFactory.createObject(org.eclipse.microprofile.openapi.models.Components.class);
            openAPI.setComponents(components);
        }

        for (Header headerAnnotation : headers) {
            String name = headerAnnotation.name();
            if (name.isEmpty()) {
                continue;
            }
            var header = OASFactory.createObject(org.eclipse.microprofile.openapi.models.headers.Header.class);
            if (!headerAnnotation.ref().isEmpty()) {
                header.setRef(headerAnnotation.ref());
            }
            if (!headerAnnotation.description().isEmpty()) {
                header.setDescription(headerAnnotation.description());
            }
            header.setRequired(headerAnnotation.required());
            header.setDeprecated(headerAnnotation.deprecated());
            header.setAllowEmptyValue(headerAnnotation.allowEmptyValue());

            if (headerAnnotation.schema() != null) {
                header.setSchema(schemaGenerator.generate(Object.class, headerAnnotation.schema()));
            }
            components.addHeader(name, header);
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
