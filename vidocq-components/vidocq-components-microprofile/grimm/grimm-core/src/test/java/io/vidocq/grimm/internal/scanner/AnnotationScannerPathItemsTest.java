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
import org.eclipse.microprofile.openapi.annotations.Components;
import org.eclipse.microprofile.openapi.annotations.ExternalDocumentation;
import org.eclipse.microprofile.openapi.annotations.OpenAPIDefinition;
import org.eclipse.microprofile.openapi.annotations.PathItem;
import org.eclipse.microprofile.openapi.annotations.PathItemOperation;
import org.eclipse.microprofile.openapi.annotations.callbacks.Callback;
import org.eclipse.microprofile.openapi.annotations.callbacks.CallbackOperation;
import org.eclipse.microprofile.openapi.annotations.enums.ParameterIn;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.extensions.Extension;
import org.eclipse.microprofile.openapi.annotations.info.Info;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirementsSet;
import org.eclipse.microprofile.openapi.annotations.servers.Server;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.Operation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code @Components.pathItems} (MP OpenAPI 4.2 §5.1, Path Item objects as reusable
 * components): operations with their tags, servers, parameters, security, request body,
 * responses and callbacks.
 */
class AnnotationScannerPathItemsTest {

    private static final String X_PART = "x-part";
    private static final String ITEM = "petItem";
    private static final String NAMELESS = "nameless, ignored";

    @OpenAPIDefinition(info = @Info(title = "Path items", version = "1"), components = @Components(pathItems = {
            @PathItem(name = ITEM, ref = "#/components/pathItems/base", summary = "Pet item",
                    description = "Reusable pet path",
                    servers = {@Server(url = "https://items.example.com", description = "Items"),
                            @Server(url = "https://plain.example.com"), @Server(description = NAMELESS)},
                    parameters = {
                            @Parameter(name = "petId", in = ParameterIn.PATH, required = true, description = "Pet id",
                                    schema = @Schema(type = SchemaType.STRING),
                                    extensions = @Extension(name = X_PART, value = "item-parameter")),
                            @Parameter(name = "trace"),
                            @Parameter(description = NAMELESS)
                    },
                    extensions = @Extension(name = X_PART, value = "item"),
                    operations = {
                            @PathItemOperation(method = "get", operationId = "getPet", summary = "Get",
                                    description = "Gets a pet", deprecated = true,
                                    externalDocs = @ExternalDocumentation(url = "https://example.com/get"),
                                    tags = {@Tag(name = "pets", description = "Pet operations"), @Tag(name = "plainTag"),
                                            @Tag(ref = "refTag"), @Tag(description = NAMELESS)},
                                    servers = {@Server(url = "https://op.example.com", description = "Op server",
                                            extensions = @Extension(name = X_PART, value = "server")),
                                            @Server(url = "https://op-plain.example.com"),
                                            @Server(description = NAMELESS)},
                                    parameters = {
                                            @Parameter(name = "q", in = ParameterIn.QUERY, required = true,
                                                    description = "Query", schema = @Schema(type = SchemaType.INTEGER),
                                                    extensions = @Extension(name = X_PART, value = "parameter")),
                                            @Parameter(name = "plain"),
                                            @Parameter(description = NAMELESS)
                                    },
                                    security = {@SecurityRequirement(name = "oauth", scopes = "read"),
                                            @SecurityRequirement(name = "")},
                                    securitySets = {@SecurityRequirementsSet,
                                            @SecurityRequirementsSet({@SecurityRequirement(name = "key"),
                                                    @SecurityRequirement(name = "")}),
                                            @SecurityRequirementsSet(@SecurityRequirement(name = ""))},
                                    requestBody = @RequestBody(ref = "#/components/requestBodies/pet",
                                            description = "Body", required = true,
                                            content = {@Content(mediaType = "application/json",
                                                    schema = @Schema(type = SchemaType.OBJECT)), @Content},
                                            extensions = @Extension(name = X_PART, value = "body")),
                                    responses = {@APIResponse(responseCode = "200", description = "OK",
                                            ref = "#/components/responses/ok"), @APIResponse},
                                    callbacks = {
                                            @Callback(name = "onPet", ref = "#/components/callbacks/pet",
                                                    callbackUrlExpression = "{$request.body#/hook}",
                                                    operations = {@CallbackOperation(method = "post",
                                                            summary = "Hook", description = "Calls back",
                                                            responses = {@APIResponse(responseCode = "202",
                                                                    description = "Accepted",
                                                                    ref = "#/components/responses/accepted"),
                                                                    @APIResponse}),
                                                            @CallbackOperation(method = "delete")},
                                                    extensions = @Extension(name = X_PART, value = "callback")),
                                            @Callback(name = "noUrl"),
                                            @Callback(callbackUrlExpression = NAMELESS)
                                    },
                                    extensions = @Extension(name = X_PART, value = "operation")),
                            @PathItemOperation(method = "post", requestBody = @RequestBody(content = @Content(mediaType = "text/plain"))),
                            @PathItemOperation(method = "put", requestBody = @RequestBody(description = "Put body")),
                            @PathItemOperation(method = "delete"),
                            @PathItemOperation(method = "patch"),
                            @PathItemOperation(method = "options"),
                            @PathItemOperation(method = "head"),
                            @PathItemOperation(method = "trace")
                    }),
            @PathItem(name = "bareItem"),
            @PathItem(name = "", summary = NAMELESS)
    }))
    static final class PathItems {
    }

    private static org.eclipse.microprofile.openapi.models.Components components() {
        OpenAPI model = new AnnotationScanner(ScanConfig.defaultConfig()).scanClasses(List.of(PathItems.class));
        return model.getComponents();
    }

    private static Operation get() {
        return components().getPathItems().get(ITEM).getGET();
    }

    @Test
    void pathItemAttributesServersAndParametersAreMapped() {
        var items = components().getPathItems();

        assertEquals(2, items.size());
        var item = items.get(ITEM);
        assertEquals("#/components/pathItems/base", item.getRef());
        assertEquals("Pet item", item.getSummary());
        assertEquals("Reusable pet path", item.getDescription());
        assertEquals("item", item.getExtensions().get(X_PART));
        assertEquals(2, item.getServers().size());
        assertEquals("Items", item.getServers().get(0).getDescription());
        assertNull(item.getServers().get(1).getDescription());
        assertEquals(2, item.getParameters().size());
        var petId = item.getParameters().get(0);
        assertEquals("petId", petId.getName());
        assertEquals(org.eclipse.microprofile.openapi.models.parameters.Parameter.In.PATH, petId.getIn());
        assertEquals(true, petId.getRequired());
        assertEquals("Pet id", petId.getDescription());
        assertNotNull(petId.getSchema());
        assertEquals("item-parameter", petId.getExtensions().get(X_PART));
        var trace = item.getParameters().get(1);
        assertNull(trace.getIn());
        assertNull(trace.getRequired());
        assertNull(trace.getSchema());
    }

    @Test
    void operationAttributesAndTagsAreMapped() {
        Operation get = get();

        assertEquals("getPet", get.getOperationId());
        assertEquals("Get", get.getSummary());
        assertEquals("Gets a pet", get.getDescription());
        assertEquals(true, get.getDeprecated());
        assertEquals("https://example.com/get", get.getExternalDocs().getUrl());
        assertEquals("operation", get.getExtensions().get(X_PART));
        assertEquals(List.of("pets", "plainTag", "refTag"), get.getTags());
    }

    @Test
    void operationTagWithDescriptionIsPromotedToTheDocument() {
        // §5.1: a tag described on an operation also appears in the top-level tags.
        OpenAPI model = new AnnotationScanner(ScanConfig.defaultConfig()).scanClasses(List.of(PathItems.class));

        assertEquals(1, model.getTags().size());
        assertEquals("Pet operations", model.getTags().get(0).getDescription());
    }

    @Test
    void operationServersAndParametersAreMapped() {
        Operation get = get();

        assertEquals(2, get.getServers().size());
        assertEquals("Op server", get.getServers().get(0).getDescription());
        assertEquals("server", get.getServers().get(0).getExtensions().get(X_PART));
        assertNull(get.getServers().get(1).getDescription());
        assertEquals(2, get.getParameters().size());
        var q = get.getParameters().get(0);
        assertEquals(org.eclipse.microprofile.openapi.models.parameters.Parameter.In.QUERY, q.getIn());
        assertEquals(true, q.getRequired());
        assertEquals("Query", q.getDescription());
        assertNotNull(q.getSchema());
        assertEquals("parameter", q.getExtensions().get(X_PART));
        assertNull(get.getParameters().get(1).getIn());
    }

    @Test
    void operationSecurityRequirementsAndSetsAreMapped() {
        var security = get().getSecurity();

        assertEquals(3, security.size());
        assertEquals(List.of("read"), security.get(0).getScheme("oauth"));
        assertNull(security.get(1).getSchemes());
        assertEquals(List.of(), security.get(2).getScheme("key"));
    }

    @Test
    void operationRequestBodyAndResponsesAreMapped() {
        Operation get = get();

        var body = get.getRequestBody();
        assertEquals("#/components/requestBodies/pet", body.getRef());
        assertEquals("Body", body.getDescription());
        assertEquals(true, body.getRequired());
        assertEquals("body", body.getExtensions().get(X_PART));
        assertNotNull(body.getContent().getMediaType("application/json").getSchema());
        assertNull(body.getContent().getMediaType("*/*").getSchema());
        var ok = get.getResponses().getAPIResponse("200");
        assertEquals("OK", ok.getDescription());
        assertEquals("#/components/responses/ok", ok.getRef());
        assertNull(get.getResponses().getAPIResponse("default").getDescription());
    }

    @Test
    void requestBodyIsCreatedFromAnySignificantAttribute() {
        var item = components().getPathItems().get(ITEM);

        assertEquals(true, item.getPOST().getRequestBody().getRequired());
        assertNotNull(item.getPOST().getRequestBody().getContent().getMediaType("text/plain"));
        assertEquals("Put body", item.getPUT().getRequestBody().getDescription());
        assertEquals(true, item.getPUT().getRequestBody().getRequired());
    }

    @Test
    void operationWithoutRequestBodyDeclaresNone() {
        // BUG-20261028-01: @PathItemOperation.requestBody defaults to @RequestBody(), whose required
        // attribute defaults to true; that default alone must not produce a Request Body Object
        // (OpenAPI 3.1 §4.8.13: content is REQUIRED), so a DELETE without a body declares none.
        var item = components().getPathItems().get(ITEM);

        assertNull(item.getDELETE().getRequestBody());
        assertNull(item.getPATCH().getRequestBody());
    }

    @Test
    void operationCallbacksAreMapped() {
        var callbacks = get().getCallbacks();

        assertEquals(2, callbacks.size());
        var onPet = callbacks.get("onPet");
        assertEquals("#/components/callbacks/pet", onPet.getRef());
        assertEquals("callback", onPet.getExtensions().get(X_PART));
        var hook = onPet.getPathItem("{$request.body#/hook}");
        assertEquals("Hook", hook.getPOST().getSummary());
        assertEquals("Calls back", hook.getPOST().getDescription());
        var accepted = hook.getPOST().getResponses().getAPIResponse("202");
        assertEquals("Accepted", accepted.getDescription());
        assertEquals("#/components/responses/accepted", accepted.getRef());
        assertNotNull(hook.getPOST().getResponses().getAPIResponse("default"));
        assertNull(hook.getDELETE().getResponses());
        var noUrl = callbacks.get("noUrl");
        assertTrue(noUrl.getPathItems() == null || noUrl.getPathItems().isEmpty());
        assertNull(noUrl.getRef());
    }

    @Test
    void everyHttpMethodIsAssigned() {
        var item = components().getPathItems().get(ITEM);

        assertEquals(8, item.getOperations().size());
        assertNotNull(item.getPATCH());
        assertNotNull(item.getOPTIONS());
        assertNotNull(item.getHEAD());
        assertNotNull(item.getTRACE());
    }

    /** BUG-20261128-50: the HTTP method was upper-cased in the default locale, so {@code options} was lost in Turkish. */
    @Test
    void everyHttpMethodIsAssignedWhateverTheDefaultLocale() {
        Locale before = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        try {
            var item = components().getPathItems().get(ITEM);

            assertEquals(8, item.getOperations().size());
            assertNotNull(item.getOPTIONS());
        } finally {
            Locale.setDefault(before);
        }
    }

    @Test
    void barePathItemHasNoAttributes() {
        var bare = components().getPathItems().get("bareItem");

        assertNull(bare.getRef());
        assertNull(bare.getSummary());
        assertNull(bare.getDescription());
        assertTrue(bare.getServers() == null || bare.getServers().isEmpty());
    }
}
