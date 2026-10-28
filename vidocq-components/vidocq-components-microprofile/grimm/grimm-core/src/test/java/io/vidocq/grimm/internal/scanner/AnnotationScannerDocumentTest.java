/*
 * Copyright 2026 Vidocq contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.vidocq.grimm.internal.scanner;

import io.vidocq.grimm.internal.config.ScanConfig;
import org.eclipse.microprofile.openapi.annotations.ExternalDocumentation;
import org.eclipse.microprofile.openapi.annotations.OpenAPIDefinition;
import org.eclipse.microprofile.openapi.annotations.PathItem;
import org.eclipse.microprofile.openapi.annotations.PathItemOperation;
import org.eclipse.microprofile.openapi.annotations.extensions.Extension;
import org.eclipse.microprofile.openapi.annotations.info.Contact;
import org.eclipse.microprofile.openapi.annotations.info.Info;
import org.eclipse.microprofile.openapi.annotations.info.License;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirementsSet;
import org.eclipse.microprofile.openapi.annotations.servers.Server;
import org.eclipse.microprofile.openapi.annotations.servers.ServerVariable;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.Operation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Document-level annotations read by {@link AnnotationScanner}: {@code @OpenAPIDefinition}
 * (MP OpenAPI 4.2 §5.1, info, tags, servers, security, webhooks, extensions), class-level
 * {@code @Tag} and {@code @ExternalDocumentation}.
 */
class AnnotationScannerDocumentTest {

    private static final String X_LEVEL = "x-level";
    private static final String APACHE = "Apache-2.0";
    private static final String SERVER_URL = "https://api.example.com";
    private static final String HOOK = "newPet";

    @OpenAPIDefinition(
            info = @Info(title = "Full", version = "2.0", description = "Full description",
                    summary = "Full summary", termsOfService = "https://example.com/tos",
                    contact = @Contact(name = "Ops", url = "https://example.com/ops", email = "ops@example.com",
                            extensions = @Extension(name = X_LEVEL, value = "contact")),
                    license = @License(name = APACHE, url = "https://example.com/license",
                            identifier = APACHE, extensions = @Extension(name = X_LEVEL, value = "license")),
                    extensions = @Extension(name = X_LEVEL, value = "info")),
            tags = {
                    @Tag(name = "pets", description = "Pet operations",
                            externalDocs = @ExternalDocumentation(url = "https://example.com/pets"),
                            extensions = {@Extension(name = X_LEVEL, value = "tag"), @Extension(name = "", value = "nameless")}),
                    @Tag(name = "pets", description = "duplicate, ignored"),
                    @Tag(description = "no name, ignored")
            },
            servers = {
                    @Server(url = SERVER_URL, description = "Production",
                            variables = {
                                    @ServerVariable(name = "port", description = "Port", defaultValue = "443",
                                            enumeration = {"443", "8443"},
                                            extensions = @Extension(name = X_LEVEL, value = "variable")),
                                    @ServerVariable(name = "bare", defaultValue = "x"),
                                    @ServerVariable(name = "", defaultValue = "ignored")
                            },
                            extensions = @Extension(name = X_LEVEL, value = "server")),
                    @Server(url = SERVER_URL, description = "duplicate, ignored"),
                    @Server(description = "no url, ignored"),
                    @Server(url = "https://bare.example.com")
            },
            security = {@SecurityRequirement(name = "oauth", scopes = "read"), @SecurityRequirement(name = "")},
            securitySets = {
                    @SecurityRequirementsSet,
                    @SecurityRequirementsSet({@SecurityRequirement(name = "key"), @SecurityRequirement(name = "")}),
                    @SecurityRequirementsSet(@SecurityRequirement(name = ""))
            },
            externalDocs = @ExternalDocumentation(description = "Guide", url = "https://example.com/guide"),
            extensions = @Extension(name = X_LEVEL, value = "document"))
    @ExternalDocumentation(url = "https://example.com/ignored-class-docs")
    static final class FullDefinition {
    }

    @OpenAPIDefinition(info = @Info(title = "Minimal", version = ""))
    @ExternalDocumentation(description = "Class docs", url = "https://example.com/class-docs")
    @Tag(name = "classTag")
    static final class MinimalDefinition {
        @Tag(name = "methodTag")
        String tagged() {
            return "tagged";
        }
    }

    @OpenAPIDefinition(info = @Info(title = "", version = "1"))
    @ExternalDocumentation(description = "url-less, ignored")
    static final class UntitledDefinition {
    }

    @OpenAPIDefinition(info = @Info(title = "Hooks", version = "1"), webhooks = {
            @PathItem(name = HOOK, ref = "#/components/pathItems/pet", description = "Pet created",
                    summary = "New pet", extensions = @Extension(name = X_LEVEL, value = "webhook"),
                    operations = {
                            @PathItemOperation(method = "post", summary = "Notify", description = "Notifies",
                                    operationId = "notifyPet",
                                    requestBody = @RequestBody(ref = "#/components/requestBodies/pet"),
                                    responses = {
                                            @APIResponse(responseCode = "200", description = "Acknowledged",
                                                    ref = "#/components/responses/ok"),
                                            @APIResponse(description = "Fallback")
                                    },
                                    extensions = @Extension(name = X_LEVEL, value = "operation")),
                            @PathItemOperation(method = "get"),
                            @PathItemOperation(method = "put"),
                            @PathItemOperation(method = "delete"),
                            @PathItemOperation(method = "patch"),
                            @PathItemOperation(method = "head"),
                            @PathItemOperation(method = "options"),
                            @PathItemOperation(method = "trace"),
                            @PathItemOperation(method = "connect")
                    }),
            @PathItem(name = "", description = "nameless, ignored"),
            @PathItem(name = "bareHook")
    })
    static final class Webhooks {
    }

    private static OpenAPI scan(Class<?>... classes) {
        return new AnnotationScanner(ScanConfig.defaultConfig()).scanClasses(List.of(classes));
    }

    @Test
    void infoContactAndLicenseAreMapped() {
        // §5.1: @OpenAPIDefinition.info populates the Info object, its Contact and License.
        var info = scan(FullDefinition.class).getInfo();

        assertEquals("Full", info.getTitle());
        assertEquals("2.0", info.getVersion());
        assertEquals("Full description", info.getDescription());
        assertEquals("Full summary", info.getSummary());
        assertEquals("https://example.com/tos", info.getTermsOfService());
        assertEquals("info", info.getExtensions().get(X_LEVEL));
        assertEquals("Ops", info.getContact().getName());
        assertEquals("https://example.com/ops", info.getContact().getUrl());
        assertEquals("ops@example.com", info.getContact().getEmail());
        assertEquals("contact", info.getContact().getExtensions().get(X_LEVEL));
        assertEquals(APACHE, info.getLicense().getName());
        assertEquals(APACHE, info.getLicense().getIdentifier());
        assertEquals("https://example.com/license", info.getLicense().getUrl());
        assertEquals("license", info.getLicense().getExtensions().get(X_LEVEL));
    }

    @Test
    void emptyContactAndLicenseAreOmitted() {
        var info = scan(MinimalDefinition.class).getInfo();

        assertEquals("Minimal", info.getTitle());
        assertNull(info.getVersion());
        assertNull(info.getContact());
        assertNull(info.getLicense());
    }

    @Test
    void untitledInfoIsNotSet() {
        OpenAPI model = scan(UntitledDefinition.class);

        assertNull(model.getInfo());
        assertNull(model.getExternalDocs());
    }

    @Test
    void tagsAreDeduplicatedAndNamelessTagsIgnored() {
        // §5.1 tags: one Tag object per distinct name.
        var tags = scan(FullDefinition.class).getTags();

        assertEquals(1, tags.size());
        var tag = tags.get(0);
        assertEquals("pets", tag.getName());
        assertEquals("Pet operations", tag.getDescription());
        assertEquals("https://example.com/pets", tag.getExternalDocs().getUrl());
        assertEquals("tag", tag.getExtensions().get(X_LEVEL));
        assertEquals(1, tag.getExtensions().size());
    }

    @Test
    void classAndMethodTagsArePromotedToTheDocument() {
        var tags = scan(MinimalDefinition.class).getTags();

        assertEquals(List.of("classTag", "methodTag"), tags.stream().map(org.eclipse.microprofile.openapi.models.tags.Tag::getName).sorted().toList());
        assertNull(tags.get(0).getDescription());
    }

    @Test
    void serversAndVariablesAreMapped() {
        // §5.1 servers: duplicates (same url) and url-less servers are ignored.
        var servers = scan(FullDefinition.class).getServers();

        assertEquals(2, servers.size());
        var production = servers.get(0);
        assertEquals(SERVER_URL, production.getUrl());
        assertEquals("Production", production.getDescription());
        assertEquals("server", production.getExtensions().get(X_LEVEL));
        assertEquals(2, production.getVariables().size());
        var port = production.getVariables().get("port");
        assertEquals("Port", port.getDescription());
        assertEquals("443", port.getDefaultValue());
        assertEquals(List.of("443", "8443"), port.getEnumeration());
        assertEquals("variable", port.getExtensions().get(X_LEVEL));
        var bare = production.getVariables().get("bare");
        assertNull(bare.getDescription());
        assertNull(bare.getEnumeration());
        assertNull(servers.get(1).getDescription());
    }

    @Test
    void securityRequirementsAndSetsAreMapped() {
        // §5.1 security / securitySets: an empty set is the "no security" alternative.
        var requirements = scan(FullDefinition.class).getSecurity();

        assertEquals(3, requirements.size());
        assertEquals(List.of("read"), requirements.get(0).getScheme("oauth"));
        assertNull(requirements.get(1).getSchemes());
        assertEquals(List.of(), requirements.get(2).getScheme("key"));
    }

    @Test
    void definitionExternalDocsAndExtensionsWinOverClassExternalDocs() {
        OpenAPI model = scan(FullDefinition.class);

        assertEquals("https://example.com/guide", model.getExternalDocs().getUrl());
        assertEquals("Guide", model.getExternalDocs().getDescription());
        assertEquals("document", model.getExtensions().get(X_LEVEL));
    }

    @Test
    void classExternalDocsApplyWhenTheDefinitionHasNone() {
        var docs = scan(MinimalDefinition.class).getExternalDocs();

        assertEquals("https://example.com/class-docs", docs.getUrl());
        assertEquals("Class docs", docs.getDescription());
    }

    @Test
    void webhookOperationsAreMappedPerHttpMethod() {
        // §5.1 webhooks: each @PathItem becomes a webhook PathItem, operations keyed by method.
        var webhooks = scan(Webhooks.class).getWebhooks();

        assertEquals(2, webhooks.size());
        var hook = webhooks.get(HOOK);
        assertEquals("#/components/pathItems/pet", hook.getRef());
        assertEquals("Pet created", hook.getDescription());
        assertEquals("New pet", hook.getSummary());
        assertEquals("webhook", hook.getExtensions().get(X_LEVEL));
        Operation post = hook.getPOST();
        assertEquals("Notify", post.getSummary());
        assertEquals("Notifies", post.getDescription());
        assertEquals("notifyPet", post.getOperationId());
        assertEquals("#/components/requestBodies/pet", post.getRequestBody().getRef());
        assertEquals("operation", post.getExtensions().get(X_LEVEL));
        var ok = post.getResponses().getAPIResponse("200");
        assertEquals("Acknowledged", ok.getDescription());
        assertEquals("#/components/responses/ok", ok.getRef());
        assertEquals("Fallback", post.getResponses().getAPIResponse("default").getDescription());
    }

    @Test
    void everyWebhookHttpMethodIsAssigned() {
        var hook = scan(Webhooks.class).getWebhooks().get(HOOK);

        assertNotNull(hook.getGET());
        assertNotNull(hook.getPUT());
        assertNotNull(hook.getDELETE());
        assertNotNull(hook.getPATCH());
        assertNotNull(hook.getHEAD());
        assertNotNull(hook.getOPTIONS());
        assertNotNull(hook.getTRACE());
        assertNull(hook.getGET().getResponses());
        assertNull(hook.getGET().getRequestBody());
        assertEquals(8, hook.getOperations().size());
    }

    @Test
    void bareWebhookHasOnlyItsName() {
        var bare = scan(Webhooks.class).getWebhooks().get("bareHook");

        assertNull(bare.getRef());
        assertNull(bare.getDescription());
        assertNull(bare.getSummary());
        assertTrue(bare.getOperations() == null || bare.getOperations().isEmpty());
    }

    @Test
    void excludedClassesAreNotScanned() {
        ScanConfig onlyOtherPackages = new ScanConfig(false, java.util.Set.of("com.example"),
                java.util.Set.of(), java.util.Set.of(), java.util.Set.of());
        OpenAPI model = new AnnotationScanner(onlyOtherPackages).scanClasses(List.of(FullDefinition.class));

        assertNull(model.getInfo());
        assertNotNull(model.getPaths());
    }

    @Test
    void scannerExposesItsSchemaRegistry() {
        AnnotationScanner scanner = new AnnotationScanner(ScanConfig.defaultConfig());

        assertSame(scanner.schemaRegistry(), scanner.schemaRegistry());
    }

    @Test
    void nullArgumentsAreRejected() {
        assertThrows(NullPointerException.class, () -> new AnnotationScanner(null));
        AnnotationScanner scanner = new AnnotationScanner(ScanConfig.defaultConfig());
        assertThrows(NullPointerException.class, () -> scanner.scanClasses(null));
    }
}
