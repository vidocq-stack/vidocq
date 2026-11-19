/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.grimm.internal.scanner;

import io.vidocq.grimm.internal.config.ScanConfig;
import io.vidocq.grimm.internal.schema.SchemaGenerator;
import io.vidocq.grimm.internal.schema.SchemaRegistry;
import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.annotations.Components;
import org.eclipse.microprofile.openapi.annotations.ExternalDocumentation;
import org.eclipse.microprofile.openapi.annotations.OpenAPIDefinition;
import org.eclipse.microprofile.openapi.annotations.callbacks.Callback;
import org.eclipse.microprofile.openapi.annotations.callbacks.CallbackOperation;
import org.eclipse.microprofile.openapi.annotations.extensions.Extension;
import org.eclipse.microprofile.openapi.annotations.PathItemOperation;
import org.eclipse.microprofile.openapi.annotations.headers.Header;
import org.eclipse.microprofile.openapi.annotations.info.Contact;
import org.eclipse.microprofile.openapi.annotations.info.Info;
import org.eclipse.microprofile.openapi.annotations.info.License;
import org.eclipse.microprofile.openapi.annotations.links.Link;
import org.eclipse.microprofile.openapi.annotations.links.LinkParameter;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Encoding;
import org.eclipse.microprofile.openapi.annotations.media.ExampleObject;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirementsSet;
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme;
import org.eclipse.microprofile.openapi.annotations.servers.Server;
import org.eclipse.microprofile.openapi.annotations.servers.ServerVariable;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.PathItem;
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

    /**
     * Reads the {@code @OpenAPIDefinition} of {@code classes} into {@code openAPI}, and nothing else: their paths come
     * from compile-time fragments (grimm BUG-20261030-02).
     */
    public void scanDefinitions(Collection<Class<?>> classes, OpenAPI openAPI) {
        for (Class<?> clazz : classes) {
            if (config.shouldScan(clazz.getName())) {
                processOpenAPIDefinition(clazz, openAPI);
            }
        }
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
        addSecurityRequirementSets(openAPI, annotation.securitySets());
        addComponents(openAPI, annotation.components());
        addWebhooks(openAPI, annotation.webhooks());
        AnnotationModelMappings.applyExtensions(openAPI, annotation.extensions());

        ExternalDocumentation externalDocs = annotation.externalDocs();
        if (!externalDocs.url().isEmpty()) {
            openAPI.setExternalDocs(AnnotationModelMappings.toModelExternalDocs(externalDocs));
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
        AnnotationModelMappings.applyExtensions(info, infoAnnotation.extensions());

        var contact = toModelContact(infoAnnotation.contact());
        if (contact != null) {
            info.setContact(contact);
        }
        var license = toModelLicense(infoAnnotation.license());
        if (license != null) {
            info.setLicense(license);
        }
    }

    /** The Contact Object of {@code contactAnnotation}, or {@code null} when it sets no field. */
    private static org.eclipse.microprofile.openapi.models.info.Contact toModelContact(Contact contactAnnotation) {
        if (contactAnnotation.name().isEmpty()
            && contactAnnotation.url().isEmpty()
            && contactAnnotation.email().isEmpty()) {
            return null;
        }
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
        AnnotationModelMappings.applyExtensions(contact, contactAnnotation.extensions());
        return contact;
    }

    /** The License Object of {@code licenseAnnotation}, or {@code null} when it sets no field. */
    private static org.eclipse.microprofile.openapi.models.info.License toModelLicense(License licenseAnnotation) {
        if (licenseAnnotation.name().isEmpty()
            && licenseAnnotation.url().isEmpty()
            && licenseAnnotation.identifier().isEmpty()) {
            return null;
        }
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
        AnnotationModelMappings.applyExtensions(license, licenseAnnotation.extensions());
        return license;
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
            openAPI.setExternalDocs(AnnotationModelMappings.toModelExternalDocs(annotation));
        }
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
            if (!tagAnnotation.externalDocs().url().isEmpty()) {
                tag.setExternalDocs(AnnotationModelMappings.toModelExternalDocs(tagAnnotation.externalDocs()));
            }
            for (Extension extension : tagAnnotation.extensions()) {
                if (!extension.name().isEmpty()) {
                    tag.addExtension(extension.name(), AnnotationModelMappings.parseExtensionValue(extension));
                }
            }
            openAPI.addTag(tag);
        }
    }

    private void addWebhooks(OpenAPI openAPI, org.eclipse.microprofile.openapi.annotations.PathItem[] webhooks) {
        if (webhooks == null) {
            return;
        }
        for (org.eclipse.microprofile.openapi.annotations.PathItem webhookAnnotation : webhooks) {
            if (!webhookAnnotation.name().isEmpty()) {
                openAPI.addWebhook(webhookAnnotation.name(), toWebhookPathItem(webhookAnnotation));
            }
        }
    }

    private PathItem toWebhookPathItem(org.eclipse.microprofile.openapi.annotations.PathItem webhookAnnotation) {
        PathItem webhookPathItem = OASFactory.createObject(PathItem.class);
        if (!webhookAnnotation.ref().isEmpty()) {
            webhookPathItem.setRef(webhookAnnotation.ref());
        }
        if (!webhookAnnotation.description().isEmpty()) {
            webhookPathItem.setDescription(webhookAnnotation.description());
        }
        if (!webhookAnnotation.summary().isEmpty()) {
            webhookPathItem.setSummary(webhookAnnotation.summary());
        }
        AnnotationModelMappings.applyExtensions(webhookPathItem, webhookAnnotation.extensions());
        for (PathItemOperation operationAnnotation : webhookAnnotation.operations()) {
            assignPathItemOperation(webhookPathItem, operationAnnotation.method(), toWebhookOperation(operationAnnotation));
        }
        return webhookPathItem;
    }

    private static org.eclipse.microprofile.openapi.models.Operation toWebhookOperation(PathItemOperation operationAnnotation) {
        var operation = OASFactory.createObject(org.eclipse.microprofile.openapi.models.Operation.class);
        if (!operationAnnotation.summary().isEmpty()) {
            operation.setSummary(operationAnnotation.summary());
        }
        if (!operationAnnotation.description().isEmpty()) {
            operation.setDescription(operationAnnotation.description());
        }
        if (!operationAnnotation.operationId().isEmpty()) {
            operation.setOperationId(operationAnnotation.operationId());
        }
        addInlineResponses(operation, operationAnnotation.responses());
        if (!operationAnnotation.requestBody().ref().isEmpty()) {
            var modelBody = OASFactory.createObject(org.eclipse.microprofile.openapi.models.parameters.RequestBody.class);
            modelBody.setRef(operationAnnotation.requestBody().ref());
            operation.setRequestBody(modelBody);
        }
        AnnotationModelMappings.applyExtensions(operation, operationAnnotation.extensions());
        return operation;
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
            if (!url.isEmpty() && !containsServer(openAPI, url)) {
                openAPI.addServer(toModelServer(serverAnnotation));
            }
        }
    }

    private static org.eclipse.microprofile.openapi.models.servers.Server toModelServer(Server serverAnnotation) {
        org.eclipse.microprofile.openapi.models.servers.Server server = OASFactory.createObject(
            org.eclipse.microprofile.openapi.models.servers.Server.class
        );
        server.setUrl(serverAnnotation.url());
        if (!serverAnnotation.description().isEmpty()) {
            server.setDescription(serverAnnotation.description());
        }
        AnnotationModelMappings.applyExtensions(server, serverAnnotation.extensions());
        for (ServerVariable variableAnnotation : serverAnnotation.variables()) {
            String variableName = variableAnnotation.name();
            if (!variableName.isEmpty()) {
                server.addVariable(variableName, toModelServerVariable(variableAnnotation));
            }
        }
        return server;
    }

    private static org.eclipse.microprofile.openapi.models.servers.ServerVariable toModelServerVariable(ServerVariable variableAnnotation) {
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
        AnnotationModelMappings.applyExtensions(variable, variableAnnotation.extensions());
        return variable;
    }

    private void addComponents(OpenAPI openAPI, Components componentsAnnotation) {
        if (componentsAnnotation == null) {
            return;
        }
        addComponentSchemas(openAPI, componentsAnnotation.schemas());
        addComponentResponses(openAPI, componentsAnnotation.responses());
        addComponentParameters(openAPI, componentsAnnotation.parameters());
        addComponentExamples(openAPI, componentsAnnotation.examples());
        addComponentRequestBodies(openAPI, componentsAnnotation.requestBodies());
        addComponentHeaders(openAPI, componentsAnnotation.headers());
        addComponentSecuritySchemes(openAPI, componentsAnnotation.securitySchemes());
        addComponentLinks(openAPI, componentsAnnotation.links());
        addComponentCallbacks(openAPI, componentsAnnotation.callbacks());
        addComponentPathItems(openAPI, componentsAnnotation.pathItems());
        AnnotationModelMappings.applyExtensions(getOrCreateComponents(openAPI), componentsAnnotation.extensions());
    }

    private org.eclipse.microprofile.openapi.models.Components getOrCreateComponents(OpenAPI openAPI) {
        org.eclipse.microprofile.openapi.models.Components components = openAPI.getComponents();
        if (components == null) {
            components = OASFactory.createObject(org.eclipse.microprofile.openapi.models.Components.class);
            openAPI.setComponents(components);
        }
        return components;
    }

    private void addComponentSchemas(OpenAPI openAPI,
                                     org.eclipse.microprofile.openapi.annotations.media.Schema[] schemas) {
        if (schemas == null || schemas.length == 0 || schemaGenerator == null) {
            return;
        }
        org.eclipse.microprofile.openapi.models.Components components = getOrCreateComponents(openAPI);
        for (org.eclipse.microprofile.openapi.annotations.media.Schema schemaAnnotation : schemas) {
            String schemaName = componentSchemaName(schemaAnnotation);
            if (!schemaAnnotation.hidden() && schemaName != null && !schemaName.isEmpty()) {
                components.addSchema(schemaName, toComponentSchema(schemaAnnotation));
            }
        }
    }

    /** The explicit name, else the simple name of the implementation, else {@code null}. */
    private static String componentSchemaName(org.eclipse.microprofile.openapi.annotations.media.Schema schemaAnnotation) {
        if (!schemaAnnotation.name().isEmpty()) {
            return schemaAnnotation.name();
        }
        Class<?> implementation = schemaAnnotation.implementation();
        return implementation != Void.class ? implementation.getSimpleName() : null;
    }

    private org.eclipse.microprofile.openapi.models.media.Schema toComponentSchema(
            org.eclipse.microprofile.openapi.annotations.media.Schema schemaAnnotation) {
        Class<?> implementation = schemaAnnotation.implementation();
        if (!schemaAnnotation.ref().isEmpty()) {
            var modelSchema = OASFactory.createObject(org.eclipse.microprofile.openapi.models.media.Schema.class);
            modelSchema.setRef(schemaAnnotation.ref());
            return modelSchema;
        }
        if (implementation != Void.class) {
            return schemaGenerator.generate(implementation, schemaAnnotation);
        }
        // Named scalar/object schemas declared in @OpenAPIDefinition.components.schemas
        // still carry schema keywords (type, format, description, ...).
        return schemaGenerator.generate(Object.class, schemaAnnotation);
    }

    @SuppressWarnings("removal")
    private void addComponentHeaders(OpenAPI openAPI, Header[] headers) {
        if (headers == null || headers.length == 0) {
            return;
        }
        org.eclipse.microprofile.openapi.models.Components components = openAPI.getComponents();
        if (components == null) {
            components = getOrCreateComponents(openAPI);
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
            AnnotationModelMappings.applyHeaderExamples(header, headerAnnotation);

            if (headerAnnotation.schema() != null) {
                header.setSchema(schemaGenerator.generate(Object.class, headerAnnotation.schema()));
            }
            AnnotationModelMappings.applyExtensions(header, headerAnnotation.extensions());
            components.addHeader(name, header);
        }
    }

    private void addComponentResponses(OpenAPI openAPI, APIResponse[] responses) {
        if (responses == null || responses.length == 0) {
            return;
        }
        var components = getOrCreateComponents(openAPI);
        for (APIResponse responseAnnotation : responses) {
            if (!responseAnnotation.name().isEmpty()) {
                components.addResponse(responseAnnotation.name(), toComponentResponse(responseAnnotation));
            }
        }
    }

    private org.eclipse.microprofile.openapi.models.responses.APIResponse toComponentResponse(APIResponse responseAnnotation) {
        var modelResponse = OASFactory.createObject(org.eclipse.microprofile.openapi.models.responses.APIResponse.class);
        if (!responseAnnotation.description().isEmpty()) {
            modelResponse.setDescription(responseAnnotation.description());
        }
        if (!responseAnnotation.ref().isEmpty()) {
            modelResponse.setRef(responseAnnotation.ref());
        }
        if (responseAnnotation.content().length > 0) {
            modelResponse.setContent(toComponentContent(responseAnnotation.content(), true));
        }
        AnnotationModelMappings.applyExtensions(modelResponse, responseAnnotation.extensions());
        return modelResponse;
    }

    /**
     * The Content Object of a component: each media type with its schema and example, and with its
     * extensions when {@code withExtensions}.
     */
    private org.eclipse.microprofile.openapi.models.media.Content toComponentContent(Content[] contentAnnotations, boolean withExtensions) {
        var content = OASFactory.createObject(org.eclipse.microprofile.openapi.models.media.Content.class);
        for (Content contentAnnotation : contentAnnotations) {
            var mediaType = toComponentMediaType(contentAnnotation);
            if (withExtensions) {
                AnnotationModelMappings.applyExtensions(mediaType, contentAnnotation.extensions());
            }
            content.addMediaType(mediaTypeName(contentAnnotation), mediaType);
        }
        return content;
    }

    private org.eclipse.microprofile.openapi.models.media.MediaType toComponentMediaType(Content contentAnnotation) {
        var mediaType = OASFactory.createObject(org.eclipse.microprofile.openapi.models.media.MediaType.class);
        if (schemaGenerator != null && hasSchemaContent(contentAnnotation.schema())) {
            mediaType.setSchema(schemaGenerator.generate(Object.class, contentAnnotation.schema()));
        }
        if (!contentAnnotation.example().isEmpty()) {
            mediaType.setExample(contentAnnotation.example());
        }
        return mediaType;
    }

    private static String mediaTypeName(Content contentAnnotation) {
        return contentAnnotation.mediaType().isEmpty() ? "*/*" : contentAnnotation.mediaType();
    }

    private void addComponentParameters(OpenAPI openAPI, Parameter[] parameters) {
        if (parameters == null || parameters.length == 0) {
            return;
        }
        var components = getOrCreateComponents(openAPI);
        for (Parameter parameterAnnotation : parameters) {
            if (!parameterAnnotation.name().isEmpty()) {
                components.addParameter(parameterAnnotation.name(), toComponentParameter(parameterAnnotation));
            }
        }
    }

    private org.eclipse.microprofile.openapi.models.parameters.Parameter toComponentParameter(Parameter parameterAnnotation) {
        var parameter = OASFactory.createObject(org.eclipse.microprofile.openapi.models.parameters.Parameter.class);
        parameter.setName(parameterAnnotation.name());
        if (!parameterAnnotation.ref().isEmpty()) {
            parameter.setRef(parameterAnnotation.ref());
        }
        if (!parameterAnnotation.description().isEmpty()) {
            parameter.setDescription(parameterAnnotation.description());
        }
        if (parameterAnnotation.required()) {
            parameter.setRequired(Boolean.TRUE);
        }
        if (schemaGenerator != null && hasSchemaContent(parameterAnnotation.schema())) {
            parameter.setSchema(schemaGenerator.generate(Object.class, parameterAnnotation.schema()));
        }
        if (parameterAnnotation.content().length > 0) {
            parameter.setContent(toComponentContent(parameterAnnotation.content(), false));
        }
        AnnotationModelMappings.applyExtensions(parameter, parameterAnnotation.extensions());
        return parameter;
    }

    private void addComponentExamples(OpenAPI openAPI, ExampleObject[] examples) {
        if (examples == null || examples.length == 0) {
            return;
        }
        var components = getOrCreateComponents(openAPI);
        for (ExampleObject exampleAnnotation : examples) {
            if (exampleAnnotation.name().isEmpty()) {
                continue;
            }
            var example = AnnotationModelMappings.toModelExample(exampleAnnotation);
            components.addExample(exampleAnnotation.name(), example);
        }
    }

    private void addComponentRequestBodies(OpenAPI openAPI, RequestBody[] requestBodies) {
        if (requestBodies == null || requestBodies.length == 0) {
            return;
        }
        var components = getOrCreateComponents(openAPI);
        for (RequestBody requestBodyAnnotation : requestBodies) {
            if (!requestBodyAnnotation.name().isEmpty()) {
                components.addRequestBody(requestBodyAnnotation.name(), toComponentRequestBody(requestBodyAnnotation));
            }
        }
    }

    private org.eclipse.microprofile.openapi.models.parameters.RequestBody toComponentRequestBody(RequestBody requestBodyAnnotation) {
        var requestBody = OASFactory.createObject(org.eclipse.microprofile.openapi.models.parameters.RequestBody.class);
        if (!requestBodyAnnotation.description().isEmpty()) {
            requestBody.setDescription(requestBodyAnnotation.description());
        }
        if (requestBodyAnnotation.required()) {
            requestBody.setRequired(Boolean.TRUE);
        } else if (requestBodyAnnotation.content().length > 0) {
            requestBody.setRequired(Boolean.FALSE);
        }
        if (!requestBodyAnnotation.ref().isEmpty()) {
            requestBody.setRef(requestBodyAnnotation.ref());
        }
        if (requestBodyAnnotation.content().length > 0) {
            requestBody.setContent(toRequestBodyContent(requestBodyAnnotation.content()));
        }
        AnnotationModelMappings.applyExtensions(requestBody, requestBodyAnnotation.extensions());
        return requestBody;
    }

    /** The Content Object of a component request body: each media type with its schema, example and encodings. */
    private org.eclipse.microprofile.openapi.models.media.Content toRequestBodyContent(Content[] contentAnnotations) {
        var content = OASFactory.createObject(org.eclipse.microprofile.openapi.models.media.Content.class);
        for (Content contentAnnotation : contentAnnotations) {
            var mediaType = toComponentMediaType(contentAnnotation);
            for (Encoding encodingAnnotation : contentAnnotation.encoding()) {
                if (!encodingAnnotation.name().isEmpty()) {
                    mediaType.addEncoding(encodingAnnotation.name(), toModelEncoding(encodingAnnotation));
                }
            }
            content.addMediaType(mediaTypeName(contentAnnotation), mediaType);
        }
        return content;
    }

    private org.eclipse.microprofile.openapi.models.media.Encoding toModelEncoding(Encoding encodingAnnotation) {
        var encoding = OASFactory.createObject(org.eclipse.microprofile.openapi.models.media.Encoding.class);
        if (!encodingAnnotation.contentType().isEmpty()) {
            encoding.setContentType(encodingAnnotation.contentType());
        }
        if (encodingAnnotation.explode()) {
            encoding.setExplode(Boolean.TRUE);
        }
        for (Header headerAnnotation : encodingAnnotation.headers()) {
            String headerName = resolveHeaderName(headerAnnotation);
            if (headerName != null && !headerName.isEmpty()) {
                encoding.addHeader(headerName, toModelHeader(headerAnnotation));
            }
        }
        return encoding;
    }

    private void addComponentSecuritySchemes(OpenAPI openAPI, SecurityScheme[] schemes) {
        if (schemes == null || schemes.length == 0) {
            return;
        }
        var components = getOrCreateComponents(openAPI);
        for (SecurityScheme schemeAnnotation : schemes) {
            if (!schemeAnnotation.securitySchemeName().isEmpty()) {
                components.addSecurityScheme(schemeAnnotation.securitySchemeName(),
                        toComponentSecurityScheme(schemeAnnotation));
            }
        }
    }

    private static org.eclipse.microprofile.openapi.models.security.SecurityScheme toComponentSecurityScheme(SecurityScheme schemeAnnotation) {
        var scheme = OASFactory.createObject(org.eclipse.microprofile.openapi.models.security.SecurityScheme.class);
        if (!schemeAnnotation.ref().isEmpty()) {
            scheme.setRef(schemeAnnotation.ref());
        }
        if (!schemeAnnotation.description().isEmpty()) {
            scheme.setDescription(schemeAnnotation.description());
        }
        if (!schemeAnnotation.apiKeyName().isEmpty()) {
            scheme.setName(schemeAnnotation.apiKeyName());
        }
        if (!schemeAnnotation.scheme().isEmpty()) {
            scheme.setScheme(schemeAnnotation.scheme());
        }
        if (schemeAnnotation.type() != org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType.DEFAULT) {
            try {
                scheme.setType(org.eclipse.microprofile.openapi.models.security.SecurityScheme.Type.valueOf(schemeAnnotation.type().name()));
            } catch (IllegalArgumentException _) {
                // Ignore incompatible enum values.
            }
        }
        if (schemeAnnotation.in() != org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeIn.DEFAULT) {
            try {
                scheme.setIn(org.eclipse.microprofile.openapi.models.security.SecurityScheme.In.valueOf(schemeAnnotation.in().name()));
            } catch (IllegalArgumentException _) {
                // Ignore incompatible enum values.
            }
        }
        AnnotationModelMappings.applyExtensions(scheme, schemeAnnotation.extensions());
        return scheme;
    }

    private void addComponentLinks(OpenAPI openAPI, Link[] links) {
        if (links == null || links.length == 0) {
            return;
        }
        var components = getOrCreateComponents(openAPI);
        for (Link linkAnnotation : links) {
            if (!linkAnnotation.name().isEmpty()) {
                components.addLink(linkAnnotation.name(), toComponentLink(linkAnnotation));
            }
        }
    }

    private static org.eclipse.microprofile.openapi.models.links.Link toComponentLink(Link linkAnnotation) {
        var link = OASFactory.createObject(org.eclipse.microprofile.openapi.models.links.Link.class);
        if (!linkAnnotation.ref().isEmpty()) {
            link.setRef(linkAnnotation.ref());
        }
        if (!linkAnnotation.description().isEmpty()) {
            link.setDescription(linkAnnotation.description());
        }
        if (!linkAnnotation.operationId().isEmpty()) {
            link.setOperationId(linkAnnotation.operationId());
        }
        for (LinkParameter parameter : linkAnnotation.parameters()) {
            if (!parameter.name().isEmpty() && !parameter.expression().isEmpty()) {
                link.addParameter(parameter.name(), parameter.expression());
            }
        }
        AnnotationModelMappings.applyExtensions(link, linkAnnotation.extensions());
        return link;
    }

    private void addComponentCallbacks(OpenAPI openAPI, Callback[] callbacks) {
        if (callbacks == null || callbacks.length == 0) {
            return;
        }
        var components = getOrCreateComponents(openAPI);
        for (Callback callbackAnnotation : callbacks) {
            if (!callbackAnnotation.name().isEmpty()) {
                components.addCallback(callbackAnnotation.name(), toComponentCallback(callbackAnnotation));
            }
        }
    }

    private org.eclipse.microprofile.openapi.models.callbacks.Callback toComponentCallback(Callback callbackAnnotation) {
        var callback = OASFactory.createObject(org.eclipse.microprofile.openapi.models.callbacks.Callback.class);
        if (!callbackAnnotation.ref().isEmpty()) {
            callback.setRef(callbackAnnotation.ref());
        }
        String expression = callbackAnnotation.callbackUrlExpression();
        if (!expression.isEmpty() && callbackAnnotation.operations().length > 0) {
            callback.addPathItem(expression, toCallbackPathItem(callbackAnnotation.operations()));
        } else if (!expression.isEmpty() && !callbackAnnotation.pathItemRef().isEmpty()) {
            var pathItem = OASFactory.createObject(PathItem.class);
            pathItem.setRef(callbackAnnotation.pathItemRef());
            callback.addPathItem(expression, pathItem);
        }
        AnnotationModelMappings.applyExtensions(callback, callbackAnnotation.extensions());
        return callback;
    }

    /** The Path Item Object of the operations of a callback: summary, description and responses. */
    private PathItem toCallbackPathItem(CallbackOperation[] operationAnnotations) {
        var pathItem = OASFactory.createObject(PathItem.class);
        for (CallbackOperation operationAnnotation : operationAnnotations) {
            var operation = OASFactory.createObject(org.eclipse.microprofile.openapi.models.Operation.class);
            if (!operationAnnotation.summary().isEmpty()) {
                operation.setSummary(operationAnnotation.summary());
            }
            if (!operationAnnotation.description().isEmpty()) {
                operation.setDescription(operationAnnotation.description());
            }
            addInlineResponses(operation, operationAnnotation.responses());
            assignPathItemOperation(pathItem, operationAnnotation.method(), operation);
        }
        return pathItem;
    }

    private void addComponentPathItems(OpenAPI openAPI, org.eclipse.microprofile.openapi.annotations.PathItem[] pathItems) {
        if (pathItems == null || pathItems.length == 0) {
            return;
        }
        var components = getOrCreateComponents(openAPI);
        for (org.eclipse.microprofile.openapi.annotations.PathItem pathItemAnnotation : pathItems) {
            if (!pathItemAnnotation.name().isEmpty()) {
                components.addPathItem(pathItemAnnotation.name(), toComponentPathItem(openAPI, pathItemAnnotation));
            }
        }
    }

    private PathItem toComponentPathItem(OpenAPI openAPI,
                                         org.eclipse.microprofile.openapi.annotations.PathItem pathItemAnnotation) {
        var pathItem = OASFactory.createObject(PathItem.class);
        if (!pathItemAnnotation.ref().isEmpty()) {
            pathItem.setRef(pathItemAnnotation.ref());
        }
        if (!pathItemAnnotation.summary().isEmpty()) {
            pathItem.setSummary(pathItemAnnotation.summary());
        }
        if (!pathItemAnnotation.description().isEmpty()) {
            pathItem.setDescription(pathItemAnnotation.description());
        }
        for (Server serverAnnotation : pathItemAnnotation.servers()) {
            if (!serverAnnotation.url().isEmpty()) {
                pathItem.addServer(toInlineServer(serverAnnotation));
            }
        }
        for (PathItemOperation operationAnnotation : pathItemAnnotation.operations()) {
            assignPathItemOperation(pathItem, operationAnnotation.method(),
                    toPathItemOperation(openAPI, operationAnnotation));
        }
        AnnotationModelMappings.applyExtensions(pathItem, pathItemAnnotation.extensions());
        for (Parameter parameterAnnotation : pathItemAnnotation.parameters()) {
            if (!parameterAnnotation.name().isEmpty()) {
                pathItem.addParameter(toInlineParameter(parameterAnnotation));
            }
        }
        return pathItem;
    }

    private org.eclipse.microprofile.openapi.models.Operation toPathItemOperation(OpenAPI openAPI,
                                                                                  PathItemOperation operationAnnotation) {
        var operation = OASFactory.createObject(org.eclipse.microprofile.openapi.models.Operation.class);
        if (!operationAnnotation.operationId().isEmpty()) {
            operation.setOperationId(operationAnnotation.operationId());
        }
        if (!operationAnnotation.summary().isEmpty()) {
            operation.setSummary(operationAnnotation.summary());
        }
        if (!operationAnnotation.description().isEmpty()) {
            operation.setDescription(operationAnnotation.description());
        }
        if (!operationAnnotation.externalDocs().url().isEmpty()) {
            operation.setExternalDocs(AnnotationModelMappings.toModelExternalDocs(operationAnnotation.externalDocs()));
        }
        if (operationAnnotation.deprecated()) {
            operation.setDeprecated(Boolean.TRUE);
        }
        applyOperationTags(openAPI, operation, operationAnnotation.tags());
        for (Server serverAnnotation : operationAnnotation.servers()) {
            if (!serverAnnotation.url().isEmpty()) {
                var server = toInlineServer(serverAnnotation);
                AnnotationModelMappings.applyExtensions(server, serverAnnotation.extensions());
                operation.addServer(server);
            }
        }
        for (Parameter parameterAnnotation : operationAnnotation.parameters()) {
            if (!parameterAnnotation.name().isEmpty()) {
                operation.addParameter(toInlineParameter(parameterAnnotation));
            }
        }
        applyOperationSecurity(operation, operationAnnotation.security(), operationAnnotation.securitySets());
        var requestBody = toOperationRequestBody(operationAnnotation.requestBody());
        if (requestBody != null) {
            operation.setRequestBody(requestBody);
        }
        addInlineResponses(operation, operationAnnotation.responses());
        for (Callback callbackAnnotation : operationAnnotation.callbacks()) {
            if (!callbackAnnotation.name().isEmpty()) {
                operation.addCallback(callbackAnnotation.name(), toOperationCallback(callbackAnnotation));
            }
        }
        AnnotationModelMappings.applyExtensions(operation, operationAnnotation.extensions());
        return operation;
    }

    private void applyOperationTags(OpenAPI openAPI,
                                    org.eclipse.microprofile.openapi.models.Operation operation,
                                    Tag[] tagAnnotations) {
        for (Tag tagAnnotation : tagAnnotations) {
            if (!tagAnnotation.name().isEmpty()) {
                operation.addTag(tagAnnotation.name());
                // Promote tag with description to top-level tags (spec §3.7).
                if (!tagAnnotation.description().isEmpty() && !containsTag(openAPI, tagAnnotation.name())) {
                    addTags(openAPI, new Tag[]{tagAnnotation});
                }
            } else if (!tagAnnotation.ref().isEmpty()) {
                operation.addTag(tagAnnotation.ref());
            }
        }
    }

    private static org.eclipse.microprofile.openapi.models.servers.Server toInlineServer(Server serverAnnotation) {
        var server = OASFactory.createObject(org.eclipse.microprofile.openapi.models.servers.Server.class);
        server.setUrl(serverAnnotation.url());
        if (!serverAnnotation.description().isEmpty()) {
            server.setDescription(serverAnnotation.description());
        }
        return server;
    }

    private org.eclipse.microprofile.openapi.models.parameters.Parameter toInlineParameter(Parameter parameterAnnotation) {
        var parameter = OASFactory.createObject(org.eclipse.microprofile.openapi.models.parameters.Parameter.class);
        parameter.setName(parameterAnnotation.name());
        if (parameterAnnotation.in() != org.eclipse.microprofile.openapi.annotations.enums.ParameterIn.DEFAULT) {
            parameter.setIn(org.eclipse.microprofile.openapi.models.parameters.Parameter.In.valueOf(parameterAnnotation.in().name()));
        }
        if (parameterAnnotation.required()) {
            parameter.setRequired(Boolean.TRUE);
        }
        if (!parameterAnnotation.description().isEmpty()) {
            parameter.setDescription(parameterAnnotation.description());
        }
        if (hasSchemaContent(parameterAnnotation.schema())) {
            parameter.setSchema(schemaGenerator.generate(Object.class, parameterAnnotation.schema()));
        }
        AnnotationModelMappings.applyExtensions(parameter, parameterAnnotation.extensions());
        return parameter;
    }

    private static void applyOperationSecurity(org.eclipse.microprofile.openapi.models.Operation operation,
                                               SecurityRequirement[] requirements,
                                               SecurityRequirementsSet[] requirementSets) {
        for (SecurityRequirement requirement : requirements) {
            if (!requirement.name().isEmpty()) {
                var modelRequirement = OASFactory.createObject(org.eclipse.microprofile.openapi.models.security.SecurityRequirement.class);
                modelRequirement.addScheme(requirement.name(), List.of(requirement.scopes()));
                operation.addSecurityRequirement(modelRequirement);
            }
        }
        for (SecurityRequirementsSet requirementSet : requirementSets) {
            var groupedRequirement = OASFactory.createObject(org.eclipse.microprofile.openapi.models.security.SecurityRequirement.class);
            boolean hasScheme = false;
            for (SecurityRequirement grouped : requirementSet.value()) {
                if (!grouped.name().isEmpty()) {
                    groupedRequirement.addScheme(grouped.name(), List.of(grouped.scopes()));
                    hasScheme = true;
                }
            }
            // An empty set is the "no security" alternative; a set of nameless requirements adds nothing.
            if (hasScheme || requirementSet.value().length == 0) {
                operation.addSecurityRequirement(groupedRequirement);
            }
        }
    }

    /**
     * Maps the {@code requestBody} of a {@code @PathItemOperation}, or returns {@code null} when it
     * declares nothing: its default, {@code @RequestBody()}, has {@code required = true}, and that
     * flag alone does not make a Request Body Object (whose {@code content} is required).
     */
    private org.eclipse.microprofile.openapi.models.parameters.RequestBody toOperationRequestBody(
            RequestBody requestBodyAnnotation) {
        if (requestBodyAnnotation.ref().isEmpty()
            && requestBodyAnnotation.description().isEmpty()
            && requestBodyAnnotation.content().length == 0) {
            return null;
        }
        var requestBody = OASFactory.createObject(org.eclipse.microprofile.openapi.models.parameters.RequestBody.class);
        if (!requestBodyAnnotation.ref().isEmpty()) {
            requestBody.setRef(requestBodyAnnotation.ref());
        }
        if (!requestBodyAnnotation.description().isEmpty()) {
            requestBody.setDescription(requestBodyAnnotation.description());
        }
        if (requestBodyAnnotation.required()) {
            requestBody.setRequired(Boolean.TRUE);
        }
        if (requestBodyAnnotation.content().length > 0) {
            var content = OASFactory.createObject(org.eclipse.microprofile.openapi.models.media.Content.class);
            for (Content contentAnnotation : requestBodyAnnotation.content()) {
                String mediaTypeName = contentAnnotation.mediaType().isEmpty() ? "*/*" : contentAnnotation.mediaType();
                var mediaType = OASFactory.createObject(org.eclipse.microprofile.openapi.models.media.MediaType.class);
                if (hasSchemaContent(contentAnnotation.schema())) {
                    mediaType.setSchema(schemaGenerator.generate(Object.class, contentAnnotation.schema()));
                }
                content.addMediaType(mediaTypeName, mediaType);
            }
            requestBody.setContent(content);
        }
        AnnotationModelMappings.applyExtensions(requestBody, requestBodyAnnotation.extensions());
        return requestBody;
    }

    private static void addInlineResponses(org.eclipse.microprofile.openapi.models.Operation operation,
                                           APIResponse[] responseAnnotations) {
        for (APIResponse responseAnnotation : responseAnnotations) {
            if (operation.getResponses() == null) {
                operation.setResponses(OASFactory.createObject(org.eclipse.microprofile.openapi.models.responses.APIResponses.class));
            }
            String code = responseAnnotation.responseCode().isEmpty() ? "default" : responseAnnotation.responseCode();
            var response = OASFactory.createObject(org.eclipse.microprofile.openapi.models.responses.APIResponse.class);
            if (!responseAnnotation.description().isEmpty()) {
                response.setDescription(responseAnnotation.description());
            }
            if (!responseAnnotation.ref().isEmpty()) {
                response.setRef(responseAnnotation.ref());
            }
            operation.getResponses().addAPIResponse(code, response);
        }
    }

    private org.eclipse.microprofile.openapi.models.callbacks.Callback toOperationCallback(Callback callbackAnnotation) {
        var callback = OASFactory.createObject(org.eclipse.microprofile.openapi.models.callbacks.Callback.class);
        if (!callbackAnnotation.ref().isEmpty()) {
            callback.setRef(callbackAnnotation.ref());
        }
        if (!callbackAnnotation.callbackUrlExpression().isEmpty() && callbackAnnotation.operations().length > 0) {
            callback.addPathItem(callbackAnnotation.callbackUrlExpression(),
                    toCallbackPathItem(callbackAnnotation.operations()));
        }
        AnnotationModelMappings.applyExtensions(callback, callbackAnnotation.extensions());
        return callback;
    }

    private void assignPathItemOperation(PathItem pathItem,
                                         String method,
                                         org.eclipse.microprofile.openapi.models.Operation operation) {
        switch (method.toUpperCase()) {
            case "GET" -> pathItem.setGET(operation);
            case "PUT" -> pathItem.setPUT(operation);
            case "POST" -> pathItem.setPOST(operation);
            case "DELETE" -> pathItem.setDELETE(operation);
            case "PATCH" -> pathItem.setPATCH(operation);
            case "OPTIONS" -> pathItem.setOPTIONS(operation);
            case "HEAD" -> pathItem.setHEAD(operation);
            case "TRACE" -> pathItem.setTRACE(operation);
            default -> {
                // Not an HTTP method of the Path Item Object: the operation is not documented.
            }
        }
    }

    private boolean hasSchemaContent(org.eclipse.microprofile.openapi.annotations.media.Schema schema) {
        return schema != null && (schema.implementation() != Void.class
            || !schema.ref().isEmpty()
            || schema.type() != org.eclipse.microprofile.openapi.annotations.enums.SchemaType.DEFAULT
            || !schema.format().isEmpty()
            || !schema.description().isEmpty()
            || !schema.title().isEmpty()
            || schema.required());
    }

    @SuppressWarnings("removal")
    private org.eclipse.microprofile.openapi.models.headers.Header toModelHeader(Header headerAnnotation) {
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
        AnnotationModelMappings.applyHeaderExamples(header, headerAnnotation);
        if (schemaGenerator != null && hasSchemaContent(headerAnnotation.schema())) {
            header.setSchema(schemaGenerator.generate(Object.class, headerAnnotation.schema()));
        }
        AnnotationModelMappings.applyExtensions(header, headerAnnotation.extensions());
        return header;
    }

    private String resolveHeaderName(Header headerAnnotation) {
        if (!headerAnnotation.name().isEmpty()) {
            return headerAnnotation.name();
        }
        if (!headerAnnotation.ref().isEmpty()) {
            int slash = headerAnnotation.ref().lastIndexOf('/');
            return slash >= 0 ? headerAnnotation.ref().substring(slash + 1) : headerAnnotation.ref();
        }
        return null;
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

    private void addSecurityRequirementSets(OpenAPI openAPI, SecurityRequirementsSet[] securitySets) {
        for (SecurityRequirementsSet securitySet : securitySets) {
            var setRequirements = securitySet.value();
            if (setRequirements.length == 0) {
                openAPI.addSecurityRequirement(
                    OASFactory.createObject(org.eclipse.microprofile.openapi.models.security.SecurityRequirement.class)
                );
                continue;
            }
            var requirement = OASFactory.createObject(org.eclipse.microprofile.openapi.models.security.SecurityRequirement.class);
            boolean hasScheme = false;
            for (SecurityRequirement requirementAnnotation : setRequirements) {
                if (!requirementAnnotation.name().isEmpty()) {
                    requirement.addScheme(requirementAnnotation.name(), List.of(requirementAnnotation.scopes()));
                    hasScheme = true;
                }
            }
            if (hasScheme) {
                openAPI.addSecurityRequirement(requirement);
            }
        }
    }
}
