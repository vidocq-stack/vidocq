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
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import org.eclipse.microprofile.openapi.annotations.ExternalDocumentation;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.callbacks.Callback;
import org.eclipse.microprofile.openapi.annotations.enums.ParameterIn;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeIn;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType;
import org.eclipse.microprofile.openapi.annotations.extensions.Extension;
import org.eclipse.microprofile.openapi.annotations.extensions.Extensions;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBodySchema;
import org.eclipse.microprofile.openapi.annotations.headers.Header;
import org.eclipse.microprofile.openapi.annotations.media.ExampleObject;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponseSchema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.security.OAuthFlow;
import org.eclipse.microprofile.openapi.annotations.security.OAuthFlows;
import org.eclipse.microprofile.openapi.annotations.security.OAuthScope;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme;
import org.eclipse.microprofile.openapi.annotations.security.SecuritySchemes;
import org.eclipse.microprofile.openapi.annotations.servers.Server;
import org.eclipse.microprofile.openapi.annotations.servers.ServerVariable;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.PathItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for JAX-RS resource + operation annotation scanning.
 *
 * Spec §3.6: @Operation
 * Spec §3.7: @Parameter
 * Spec §3.8: @RequestBody
 * Spec §3.9: @APIResponse
 */
class JaxRsResourceScannerTest {

    @Test
    void scansSimpleGetResource() {
        // Spec §3.6: a @GET method on a @Path resource creates a PathItem with a GET operation.
        AnnotationScanner scanner = new AnnotationScanner(ScanConfig.defaultConfig());
        OpenAPI model = scanner.scanClasses(List.of(SimpleResource.class));

        assertNotNull(model.getPaths());
        PathItem item = model.getPaths().getPathItem("/pets");
        assertNotNull(item);
        assertNotNull(item.getGET());
        assertEquals("listPets", item.getGET().getOperationId());
        assertEquals("List pets", item.getGET().getSummary());
    }

    @Test
    void combinesClassAndMethodPath() {
        // Spec §3.6: class @Path + method @Path concatenate.
        AnnotationScanner scanner = new AnnotationScanner(ScanConfig.defaultConfig());
        OpenAPI model = scanner.scanClasses(List.of(SubPathResource.class));

        assertNotNull(model.getPaths().getPathItem("/pets/{id}"));
        assertNotNull(model.getPaths().getPathItem("/pets/{id}").getGET());
    }

    @Test
    void supportsMultipleHttpMethods() {
        // Spec §3.6: GET, POST, PUT, DELETE on the same path produce one PathItem with each verb set.
        AnnotationScanner scanner = new AnnotationScanner(ScanConfig.defaultConfig());
        OpenAPI model = scanner.scanClasses(List.of(MultiVerbResource.class));

        PathItem item = model.getPaths().getPathItem("/things");
        assertNotNull(item.getGET());
        assertNotNull(item.getPOST());
        assertNotNull(item.getPUT());
        assertNotNull(item.getDELETE());
    }

    @Test
    void operationHiddenIsSkipped() {
        // Spec §3.6: @Operation(hidden=true) excludes the method from the model.
        AnnotationScanner scanner = new AnnotationScanner(ScanConfig.defaultConfig());
        OpenAPI model = scanner.scanClasses(List.of(HiddenOperationResource.class));

        assertTrue(model.getPaths() == null
                || model.getPaths().getPathItem("/hidden") == null
                || model.getPaths().getPathItem("/hidden").getGET() == null);
    }

    @Test
    void infersPathQueryHeaderParametersFromJaxRs() {
        // Spec §3.7: @PathParam/@QueryParam/@HeaderParam produce inferred Parameter objects.
        AnnotationScanner scanner = new AnnotationScanner(ScanConfig.defaultConfig());
        OpenAPI model = scanner.scanClasses(List.of(ParametersResource.class));

        var op = model.getPaths().getPathItem("/items/{id}").getGET();
        assertNotNull(op.getParameters());
        assertEquals(3, op.getParameters().size());

        var idParam = op.getParameters().stream().filter(p -> "id".equals(p.getName())).findFirst().orElseThrow();
        assertEquals(org.eclipse.microprofile.openapi.models.parameters.Parameter.In.PATH, idParam.getIn());
        assertEquals(Boolean.TRUE, idParam.getRequired());

        var qParam = op.getParameters().stream().filter(p -> "q".equals(p.getName())).findFirst().orElseThrow();
        assertEquals(org.eclipse.microprofile.openapi.models.parameters.Parameter.In.QUERY, qParam.getIn());

        var hParam = op.getParameters().stream().filter(p -> "X-Trace".equals(p.getName())).findFirst().orElseThrow();
        assertEquals(org.eclipse.microprofile.openapi.models.parameters.Parameter.In.HEADER, hParam.getIn());
    }

    @Test
    void explicitParameterAnnotationOverridesInference() {
        // Spec §3.7: explicit @Parameter overrides JAX-RS inference, providing description etc.
        AnnotationScanner scanner = new AnnotationScanner(ScanConfig.defaultConfig());
        OpenAPI model = scanner.scanClasses(List.of(ExplicitParameterResource.class));

        var op = model.getPaths().getPathItem("/explicit/{id}").getGET();
        var idParam = op.getParameters().stream().filter(p -> "id".equals(p.getName())).findFirst().orElseThrow();
        assertEquals("Identifier of the resource", idParam.getDescription());
        assertEquals(org.eclipse.microprofile.openapi.models.parameters.Parameter.In.PATH, idParam.getIn());
    }

    @Test
    void parameterWithHiddenTrueIsSkipped() {
        // Spec §3.7: @Parameter(hidden=true) excludes the parameter from the model.
        AnnotationScanner scanner = new AnnotationScanner(ScanConfig.defaultConfig());
        OpenAPI model = scanner.scanClasses(List.of(HiddenParameterResource.class));

        var op = model.getPaths().getPathItem("/hidden-param").getGET();
        // q parameter has hidden=true, so only the implicit path/query left? Only 'q' query exists, should be absent.
        assertTrue(op.getParameters() == null || op.getParameters().isEmpty());
    }

    @Test
    void infersRequestBodyFromEntityParameter() {
        // Spec §3.8: a JAX-RS body parameter (no JAX-RS param annotation) yields a RequestBody;
        // @Consumes provides media types.
        AnnotationScanner scanner = new AnnotationScanner(ScanConfig.defaultConfig());
        OpenAPI model = scanner.scanClasses(List.of(RequestBodyResource.class));

        var op = model.getPaths().getPathItem("/items").getPOST();
        assertNotNull(op.getRequestBody());
        assertNotNull(op.getRequestBody().getContent());
        assertNotNull(op.getRequestBody().getContent().getMediaType("application/json"));
    }

    @Test
    void explicitRequestBodyAnnotationApplies() {
        // Spec §3.8: @RequestBody on a parameter overrides inference (description, required).
        AnnotationScanner scanner = new AnnotationScanner(ScanConfig.defaultConfig());
        OpenAPI model = scanner.scanClasses(List.of(ExplicitRequestBodyResource.class));

        var op = model.getPaths().getPathItem("/explicit-body").getPOST();
        assertNotNull(op.getRequestBody());
        assertEquals("Payload for creation", op.getRequestBody().getDescription());
        assertEquals(Boolean.TRUE, op.getRequestBody().getRequired());
    }

    @Test
    void apiResponseAnnotationsProduceResponses() {
        // Spec §3.9: @APIResponse / @APIResponses populate operation responses.
        AnnotationScanner scanner = new AnnotationScanner(ScanConfig.defaultConfig());
        OpenAPI model = scanner.scanClasses(List.of(ResponsesResource.class));

        var op = model.getPaths().getPathItem("/responses").getGET();
        assertNotNull(op.getResponses());
        assertNotNull(op.getResponses().getAPIResponse("200"));
        assertEquals("OK", op.getResponses().getAPIResponse("200").getDescription());
        assertNotNull(op.getResponses().getAPIResponse("404"));
        assertEquals("Not found", op.getResponses().getAPIResponse("404").getDescription());
    }

    @Test
    void inferredDefaultResponseWhenNoneDeclared() {
        // Spec §3.9: when no @APIResponse is provided, a default 200 response is inferred.
        AnnotationScanner scanner = new AnnotationScanner(ScanConfig.defaultConfig());
        OpenAPI model = scanner.scanClasses(List.of(SimpleResource.class));

        var op = model.getPaths().getPathItem("/pets").getGET();
        assertNotNull(op.getResponses());
        assertNotNull(op.getResponses().getAPIResponse("200"));
    }

    @Test
    void callbackAnnotationsAreProcessed() {
        // Spec §3.6.6: @Callback on an operation produces a callbacks entry.
        AnnotationScanner scanner = new AnnotationScanner(ScanConfig.defaultConfig());
        OpenAPI model = scanner.scanClasses(List.of(CallbackResource.class));

        var op = model.getPaths().getPathItem("/subscribe").getPOST();
        assertNotNull(op.getCallbacks());
        assertNotNull(op.getCallbacks().get("event"));
    }

    @Test
    void operationServersIncludeServerVariables() {
        // Spec §3.5: @Server variables are part of the operation-level server object.
        AnnotationScanner scanner = new AnnotationScanner(ScanConfig.defaultConfig());
        OpenAPI model = scanner.scanClasses(List.of(ServerVariablesResource.class));

        var server = model.getPaths().getPathItem("/reviews/{id}").getDELETE().getServers().stream()
                .filter(s -> "{protocol}://test-server.com".equals(s.getUrl()))
                .findFirst()
                .orElseThrow();
        assertNotNull(server.getVariables());
        assertNotNull(server.getVariables().get("protocol"));
        assertEquals("https", server.getVariables().get("protocol").getDefaultValue());
        assertEquals(2, server.getVariables().get("protocol").getEnumeration().size());
    }

    @Test
    void operationDeprecatedAndTagsApplied() {
        // Spec §3.6: deprecated and tags on @Operation are applied; missing operationId derived from method name.
        AnnotationScanner scanner = new AnnotationScanner(ScanConfig.defaultConfig());
        OpenAPI model = scanner.scanClasses(List.of(DeprecatedTaggedResource.class));

        var op = model.getPaths().getPathItem("/deprecated").getGET();
        assertEquals(Boolean.TRUE, op.getDeprecated());
        assertTrue(op.getTags() != null && op.getTags().contains("legacy"));
    }

    @Test
    void operationTagsIncludeTagRefFromClassLevel() {
        AnnotationScanner scanner = new AnnotationScanner(ScanConfig.defaultConfig());
        OpenAPI model = scanner.scanClasses(List.of(TagRefResource.class));

        var op = model.getPaths().getPathItem("/tag-ref/{id}").getGET();
        assertNotNull(op.getTags());
        assertTrue(op.getTags().contains("ChildTag"));
        assertTrue(!op.getTags().contains("ParentTag"));
    }

    @Test
    void apiResponseHeadersAndLinksAreMapped() {
        AnnotationScanner scanner = new AnnotationScanner(ScanConfig.defaultConfig());
        OpenAPI model = scanner.scanClasses(List.of(ResponseLinksHeadersResource.class));

        var response = model.getPaths().getPathItem("/responses-links").getGET()
                .getResponses().getAPIResponse("200");
        assertNotNull(response);
        assertNotNull(response.getHeaders());
        assertNotNull(response.getHeaders().get("Max-Rate"));
        assertEquals("Maximum rate", response.getHeaders().get("Max-Rate").getDescription());
        assertNotNull(response.getLinks());
        assertNotNull(response.getLinks().get("User name"));
        assertEquals("getUserByName", response.getLinks().get("User name").getOperationId());
    }

    @Test
    void resourceWithoutPathIsIgnored() {
        // Spec §3.6: a class without @Path produces no path items.
        AnnotationScanner scanner = new AnnotationScanner(ScanConfig.defaultConfig());
        OpenAPI model = scanner.scanClasses(List.of(NoPathClass.class));

        assertTrue(model.getPaths() == null || model.getPaths().getPathItems() == null || model.getPaths().getPathItems().isEmpty());
    }

    @Test
    void classSecuritySchemesAreMappedToComponents() {
        AnnotationScanner scanner = new AnnotationScanner(ScanConfig.defaultConfig());
        OpenAPI model = scanner.scanClasses(List.of(SecurityResource.class));

        assertNotNull(model.getComponents());
        assertNotNull(model.getComponents().getSecuritySchemes());
        var apiKey = model.getComponents().getSecuritySchemes().get("petsApiKey");
        assertNotNull(apiKey);
        assertEquals(org.eclipse.microprofile.openapi.models.security.SecurityScheme.Type.APIKEY, apiKey.getType());
        assertEquals(org.eclipse.microprofile.openapi.models.security.SecurityScheme.In.HEADER, apiKey.getIn());
        assertEquals("api_key", apiKey.getName());

        var oauth2 = model.getComponents().getSecuritySchemes().get("petsOAuth2");
        assertNotNull(oauth2);
        assertNotNull(oauth2.getFlows());
        assertNotNull(oauth2.getFlows().getImplicit());
        assertEquals("https://example.com/api/oauth/dialog", oauth2.getFlows().getImplicit().getAuthorizationUrl());
        assertEquals("write:pets", oauth2.getFlows().getImplicit().getScopes().keySet().stream().findFirst().orElseThrow());
    }

    @Test
    void classAndMethodSecurityRequirementsAreAppliedToOperation() {
        AnnotationScanner scanner = new AnnotationScanner(ScanConfig.defaultConfig());
        OpenAPI model = scanner.scanClasses(List.of(SecurityResource.class));

        var op = model.getPaths().getPathItem("/secure/{id}").getDELETE();
        assertNotNull(op.getSecurity());
        assertTrue(op.getSecurity().stream().anyMatch(sec -> sec.getScheme("petsOAuth2") != null));
        assertTrue(op.getSecurity().stream().anyMatch(sec -> sec.getScheme("petsApiKey") != null));
    }

    @Test
    void operationExtensionsAndApiResponseSchemaAreMapped() {
        AnnotationScanner scanner = new AnnotationScanner(ScanConfig.defaultConfig());
        OpenAPI model = scanner.scanClasses(List.of(ExtensionAndResponseSchemaResource.class));

        var op = model.getPaths().getPathItem("/response-schema/{id}").getPOST();
        assertEquals("test-operation-ext", op.getExtension("x-operation-ext"));
        var response = op.getResponses().getAPIResponse("204");
        assertNotNull(response);
        assertNotNull(response.getContent());
        var mediaType = response.getContent().getMediaType("application/json");
        assertNotNull(mediaType);
        assertNotNull(mediaType.getSchema());
        assertEquals("#/components/schemas/Payload", mediaType.getSchema().getRef());
    }

    @Test
    void requestBodySchemaAnnotationOverridesBodyInference() {
        AnnotationScanner scanner = new AnnotationScanner(ScanConfig.defaultConfig());
        OpenAPI model = scanner.scanClasses(List.of(RequestBodySchemaResource.class));

        var op = model.getPaths().getPathItem("/request-schema").getPOST();
        var mediaType = op.getRequestBody().getContent().getMediaType("application/json");
        assertNotNull(mediaType);
        assertNotNull(mediaType.getSchema());
        assertEquals("#/components/schemas/Payload", mediaType.getSchema().getRef());
    }

    @Test
    void apiResponsesExtensionsAreAppliedOnResponsesObject() {
        AnnotationScanner scanner = new AnnotationScanner(ScanConfig.defaultConfig());
        OpenAPI model = scanner.scanClasses(List.of(ResponseExtensionsResource.class));

        var op = model.getPaths().getPathItem("/responses-ext").getGET();
        assertNotNull(op.getResponses());
        assertEquals("test-responses-ext", op.getResponses().getExtension("x-responses-ext"));
    }

    @Test
    void apiResponseSchemaWithoutCodeTargetsSuccessResponse() {
        AnnotationScanner scanner = new AnnotationScanner(ScanConfig.defaultConfig());
        OpenAPI model = scanner.scanClasses(List.of(DefaultResponseSchemaResource.class));

        var op = model.getPaths().getPathItem("/response-schema-default").getGET();
        assertNotNull(op.getResponses().getAPIResponse("200"));
        assertNull(op.getResponses().getAPIResponse("default"));
        var schema = op.getResponses().getAPIResponse("200")
                .getContent().getMediaType("application/json").getSchema();
        assertNotNull(schema);
        assertNotNull(schema.getItems());
        assertEquals("#/components/schemas/Payload", schema.getItems().getRef());
    }

    @Test
    void apiResponseContentExtensionsAreMappedToMediaType() {
        AnnotationScanner scanner = new AnnotationScanner(ScanConfig.defaultConfig());
        OpenAPI model = scanner.scanClasses(List.of(ResponseContentExtensionResource.class));

        var mediaType = model.getPaths().getPathItem("/responses-content-ext").getGET()
                .getResponses().getAPIResponse("503")
                .getContent().getMediaType("application/json");
        assertNotNull(mediaType);
        assertEquals("true", mediaType.getExtension("x-notavailable-ext"));
    }

    @Test
    void standaloneExtensionsAnnotationIsAppliedToOperation() {
        AnnotationScanner scanner = new AnnotationScanner(ScanConfig.defaultConfig());
        OpenAPI model = scanner.scanClasses(List.of(StandaloneExtensionsResource.class));

        var op = model.getPaths().getPathItem("/ext").getGET();
        assertEquals("ok", op.getExtension("x-op-ext"));
    }

    @Test
    void beanValidationOnParameterIsMappedToSchema() {
        AnnotationScanner scanner = new AnnotationScanner(ScanConfig.defaultConfig());
        OpenAPI model = scanner.scanClasses(List.of(BeanValidationParameterResource.class));

        var op = model.getPaths().getPathItem("/validation/{test}").getPOST();
        var parameter = op.getParameters().stream().filter(p -> "test".equals(p.getName())).findFirst().orElseThrow();
        assertNotNull(parameter.getSchema());
        assertEquals(Integer.valueOf(6), parameter.getSchema().getMaxLength());
        assertEquals(Boolean.TRUE, parameter.getRequired());
    }

    // A @Schema that sets one attribute only must reach the generated schema, whichever attribute.

    private static org.eclipse.microprofile.openapi.models.parameters.Parameter schemaOnlyParameter(String path,
                                                                                                      String name) {
        OpenAPI model = new AnnotationScanner(ScanConfig.defaultConfig()).scanClasses(List.of(SchemaOnlyResource.class));
        return model.getPaths().getPathItem(path).getGET().getParameters().stream()
                .filter(p -> name.equals(p.getName())).findFirst().orElseThrow();
    }

    @Test
    void parameterSchemaWithOnlyMinimumIsMapped() {
        var schema = schemaOnlyParameter("/schema-only/min", "n").getSchema();

        assertNotNull(schema.getMinimum());
        assertEquals(0, schema.getMinimum().intValue());
    }

    @Test
    void parameterSchemaWithOnlyExamplesIsMapped() {
        var schema = schemaOnlyParameter("/schema-only/examples", "q").getSchema();

        assertEquals(List.of("x"), schema.getExamples());
    }

    @Test
    void parameterSchemaWithOnlyOneOfIsMapped() {
        var schema = schemaOnlyParameter("/schema-only/one-of", "o").getSchema();

        assertNotNull(schema.getOneOf());
        assertEquals(1, schema.getOneOf().size());
    }

    @Test
    void parameterSchemaWithOnlyReadOnlyIsMapped() {
        var schema = schemaOnlyParameter("/schema-only/read-only", "r").getSchema();

        assertEquals(Boolean.TRUE, schema.getReadOnly());
    }

    @Test
    void emptyParameterSchemaGivesTheInferredSchemaOnly() {
        var schema = schemaOnlyParameter("/schema-only/empty", "z").getSchema();

        assertEquals(1, schema.getAll().size());
        assertEquals(List.of(org.eclipse.microprofile.openapi.models.media.Schema.SchemaType.STRING), schema.getType());
    }

    @Test
    void requestBodySchemaWithOnlyMinimumIsMapped() {
        OpenAPI model = new AnnotationScanner(ScanConfig.defaultConfig()).scanClasses(List.of(SchemaOnlyResource.class));
        var schema = model.getPaths().getPathItem("/schema-only/body").getPOST().getRequestBody().getContent()
                .getMediaType("application/json").getSchema();

        assertNotNull(schema.getMinimum());
        assertEquals(1, schema.getMinimum().intValue());
    }

    @Test
    void requestBodySchemaWithOnlyExamplesIsMapped() {
        OpenAPI model = new AnnotationScanner(ScanConfig.defaultConfig()).scanClasses(List.of(SchemaOnlyResource.class));
        var schema = model.getPaths().getPathItem("/schema-only/body-examples").getPOST().getRequestBody().getContent()
                .getMediaType("application/json").getSchema();

        assertEquals(List.of("x"), schema.getExamples());
    }

    @Test
    void emptyRequestBodySchemaGivesTheInferredSchemaOnly() {
        OpenAPI model = new AnnotationScanner(ScanConfig.defaultConfig()).scanClasses(List.of(SchemaOnlyResource.class));
        var schema = model.getPaths().getPathItem("/schema-only/body-empty").getPOST().getRequestBody().getContent()
                .getMediaType("application/json").getSchema();

        assertEquals(1, schema.getAll().size());
    }

    private static OpenAPI scanSchemaSites() {
        return new AnnotationScanner(ScanConfig.defaultConfig()).scanClasses(List.of(SchemaSitesResource.class));
    }

    private static org.eclipse.microprofile.openapi.models.media.Schema responseHeaderSchema(String path) {
        return scanSchemaSites().getPaths().getPathItem(path).getGET().getResponses().getAPIResponse("200")
                .getHeaders().get("X").getSchema();
    }

    private static org.eclipse.microprofile.openapi.models.media.Schema responseContentSchema(String path) {
        return scanSchemaSites().getPaths().getPathItem(path).getGET().getResponses().getAPIResponse("200")
                .getContent().getMediaType("application/json").getSchema();
    }

    private static org.eclipse.microprofile.openapi.models.headers.Header encodingHeader(String path) {
        return scanSchemaSites().getPaths().getPathItem(path).getPOST().getRequestBody().getContent()
                .getMediaType("multipart/form-data").getEncoding().get("f").getHeaders().get("X");
    }

    @Test
    void responseHeaderSchemaWithOnlyMinimumIsMapped() {
        assertEquals(0, responseHeaderSchema("/sites/header-min").getMinimum().intValue());
    }

    @Test
    void responseHeaderSchemaWithOnlyExamplesIsMapped() {
        assertEquals(List.of("x"), responseHeaderSchema("/sites/header-examples").getExamples());
    }

    @Test
    void emptyResponseHeaderSchemaIsTheSameAsNone() {
        assertNull(responseHeaderSchema("/sites/header-empty"));
    }

    @Test
    void contentSchemaWithOnlyMinimumIsMapped() {
        assertEquals(0, responseContentSchema("/sites/content-min").getMinimum().intValue());
    }

    @Test
    void contentSchemaWithOnlyExamplesIsMapped() {
        assertEquals(List.of("x"), responseContentSchema("/sites/content-examples").getExamples());
    }

    @Test
    void emptyContentSchemaIsTheSameAsNone() {
        var empty = responseContentSchema("/sites/content-empty");
        var none = responseContentSchema("/sites/content-none");

        assertEquals(none == null ? null : none.getAll(), empty == null ? null : empty.getAll());
    }

    @Test
    void encodingHeaderSchemaWithOnlyMinimumIsMapped() {
        assertEquals(0, encodingHeader("/sites/enc-min").getSchema().getMinimum().intValue());
    }

    @Test
    void encodingHeaderSchemaWithOnlyExamplesIsMapped() {
        assertEquals(List.of("x"), encodingHeader("/sites/enc-examples").getSchema().getExamples());
    }

    @Test
    void emptyEncodingHeaderSchemaIsTheSameAsNone() {
        assertNull(encodingHeader("/sites/enc-empty").getSchema());
    }

    // ---------- Fixtures ----------


    @Path("/sites")
    static class SchemaSitesResource {
        @GET @Path("/header-min")
        @APIResponse(responseCode = "200", headers = @org.eclipse.microprofile.openapi.annotations.headers.Header(name = "X", schema = @org.eclipse.microprofile.openapi.annotations.media.Schema(minimum = "0")))
        public String headerMin() { return null; }

        @GET @Path("/header-examples")
        @APIResponse(responseCode = "200", headers = @org.eclipse.microprofile.openapi.annotations.headers.Header(name = "X", schema = @org.eclipse.microprofile.openapi.annotations.media.Schema(examples = "x")))
        public String headerExamples() { return null; }

        @GET @Path("/header-empty")
        @APIResponse(responseCode = "200", headers = @org.eclipse.microprofile.openapi.annotations.headers.Header(name = "X", schema = @org.eclipse.microprofile.openapi.annotations.media.Schema))
        public String headerEmpty() { return null; }

        @GET @Path("/content-min") @Produces("application/json")
        @APIResponse(responseCode = "200", content = @org.eclipse.microprofile.openapi.annotations.media.Content(mediaType = "application/json", schema = @org.eclipse.microprofile.openapi.annotations.media.Schema(minimum = "0")))
        public String contentMin() { return null; }

        @GET @Path("/content-examples") @Produces("application/json")
        @APIResponse(responseCode = "200", content = @org.eclipse.microprofile.openapi.annotations.media.Content(mediaType = "application/json", schema = @org.eclipse.microprofile.openapi.annotations.media.Schema(examples = "x")))
        public String contentExamples() { return null; }

        @GET @Path("/content-empty") @Produces("application/json")
        @APIResponse(responseCode = "200", content = @org.eclipse.microprofile.openapi.annotations.media.Content(mediaType = "application/json", schema = @org.eclipse.microprofile.openapi.annotations.media.Schema))
        public String contentEmpty() { return null; }

        @GET @Path("/content-none") @Produces("application/json")
        @APIResponse(responseCode = "200", content = @org.eclipse.microprofile.openapi.annotations.media.Content(mediaType = "application/json"))
        public String contentNone() { return null; }

        @POST @Path("/enc-min") @Consumes("multipart/form-data")
        @RequestBody(content = @org.eclipse.microprofile.openapi.annotations.media.Content(mediaType = "multipart/form-data", encoding = @org.eclipse.microprofile.openapi.annotations.media.Encoding(name = "f",
                headers = @org.eclipse.microprofile.openapi.annotations.headers.Header(name = "X", schema = @org.eclipse.microprofile.openapi.annotations.media.Schema(minimum = "0")))))
        public void encMin(String body) { }

        @POST @Path("/enc-examples") @Consumes("multipart/form-data")
        @RequestBody(content = @org.eclipse.microprofile.openapi.annotations.media.Content(mediaType = "multipart/form-data", encoding = @org.eclipse.microprofile.openapi.annotations.media.Encoding(name = "f",
                headers = @org.eclipse.microprofile.openapi.annotations.headers.Header(name = "X", schema = @org.eclipse.microprofile.openapi.annotations.media.Schema(examples = "x")))))
        public void encExamples(String body) { }

        @POST @Path("/enc-empty") @Consumes("multipart/form-data")
        @RequestBody(content = @org.eclipse.microprofile.openapi.annotations.media.Content(mediaType = "multipart/form-data", encoding = @org.eclipse.microprofile.openapi.annotations.media.Encoding(name = "f",
                headers = @org.eclipse.microprofile.openapi.annotations.headers.Header(name = "X", schema = @org.eclipse.microprofile.openapi.annotations.media.Schema))))
        public void encEmpty(String body) { }
    }

    @Path("/schema-only")
    static class SchemaOnlyResource {
        @GET @Path("/min")
        public String min(@QueryParam("n") @org.eclipse.microprofile.openapi.annotations.media.Schema(minimum = "0") int n) {
            return null;
        }

        @GET @Path("/examples")
        public String examples(@QueryParam("q") @org.eclipse.microprofile.openapi.annotations.media.Schema(examples = "x") String q) {
            return null;
        }

        @GET @Path("/one-of")
        public String oneOf(@QueryParam("o") @org.eclipse.microprofile.openapi.annotations.media.Schema(oneOf = String.class) Object o) {
            return null;
        }

        @GET @Path("/read-only")
        public String readOnly(@QueryParam("r") @org.eclipse.microprofile.openapi.annotations.media.Schema(readOnly = true) String r) {
            return null;
        }

        @GET @Path("/empty")
        public String empty(@QueryParam("z") @org.eclipse.microprofile.openapi.annotations.media.Schema String z) {
            return null;
        }

        @POST @Path("/body") @Consumes("application/json")
        public void body(@org.eclipse.microprofile.openapi.annotations.media.Schema(minimum = "1") int body) {
        }

        @POST @Path("/body-examples") @Consumes("application/json")
        public void bodyExamples(@org.eclipse.microprofile.openapi.annotations.media.Schema(examples = "x") String body) {
        }

        @POST @Path("/body-empty") @Consumes("application/json")
        public void bodyEmpty(@org.eclipse.microprofile.openapi.annotations.media.Schema String body) {
        }
    }

    @Path("/pets")
    static class SimpleResource {
        @GET
        @Operation(operationId = "listPets", summary = "List pets")
        public Object list() { return null; }
    }

    @Path("/pets")
    static class SubPathResource {
        @GET
        @Path("/{id}")
        public Object get(@PathParam("id") String id) { return null; }
    }

    @Path("/things")
    static class MultiVerbResource {
        @GET public Object g() { return null; }
        @POST public Object p() { return null; }
        @PUT public Object u() { return null; }
        @DELETE public Object d() { return null; }
    }

    @Path("/hidden")
    static class HiddenOperationResource {
        @GET
        @Operation(hidden = true)
        public Object g() { return null; }
    }

    @Path("/items")
    static class ParametersResource {
        @GET
        @Path("/{id}")
        public Object get(
                @PathParam("id") String id,
                @QueryParam("q") String q,
                @HeaderParam("X-Trace") String trace) {
            return null;
        }
    }

    @Path("/explicit")
    static class ExplicitParameterResource {
        @GET
        @Path("/{id}")
        public Object get(
                @Parameter(name = "id", in = ParameterIn.PATH, description = "Identifier of the resource")
                @PathParam("id") String id) {
            return null;
        }
    }

    @Path("/hidden-param")
    static class HiddenParameterResource {
        @GET
        public Object get(
                @Parameter(name = "q", hidden = true)
                @QueryParam("q") String q) {
            return null;
        }
    }

    @Path("/items")
    static class RequestBodyResource {
        @POST
        @Consumes("application/json")
        @Produces("application/json")
        public Object create(Payload payload) { return null; }
    }

    @Path("/explicit-body")
    static class ExplicitRequestBodyResource {
        @POST
        @Consumes("application/json")
        public Object create(
                @RequestBody(description = "Payload for creation", required = true) Payload payload) {
            return null;
        }
    }

    @Path("/responses")
    static class ResponsesResource {
        @GET
        @APIResponses({
                @APIResponse(responseCode = "200", description = "OK"),
                @APIResponse(responseCode = "404", description = "Not found")
        })
        public Object g() { return null; }
    }

    @Path("/subscribe")
    static class CallbackResource {
        @POST
        @Callback(name = "event", callbackUrlExpression = "{$request.body#/url}",
                operations = @org.eclipse.microprofile.openapi.annotations.callbacks.CallbackOperation(
                        method = "POST", summary = "Event"))
        public Object subscribe(Payload p) { return null; }
    }

    @Path("/reviews/{id}")
    static class ServerVariablesResource {
        @DELETE
        @Server(
            url = "{protocol}://test-server.com",
            variables = @ServerVariable(name = "protocol", defaultValue = "https", enumeration = {"http", "https"})
        )
        public void delete(@PathParam("id") String id) {
        }
    }

    @Path("/deprecated")
    static class DeprecatedTaggedResource {
        @GET
        @Operation(deprecated = true)
        @org.eclipse.microprofile.openapi.annotations.tags.Tag(name = "legacy")
        public Object g() { return null; }
    }

    @Path("/tag-ref")
    @org.eclipse.microprofile.openapi.annotations.tags.Tag(ref = "ParentTag")
    static class TagRefResource {
        @GET
        @Path("/{id}")
        @org.eclipse.microprofile.openapi.annotations.tags.Tag(name = "ChildTag")
        public Object get(@PathParam("id") String id) {
            return null;
        }
    }

    @Path("/responses-links")
    static class ResponseLinksHeadersResource {
        @GET
        @APIResponse(
                responseCode = "200",
                description = "OK",
                headers = @org.eclipse.microprofile.openapi.annotations.headers.Header(
                        name = "Max-Rate",
                        description = "Maximum rate"
                ),
                links = @org.eclipse.microprofile.openapi.annotations.links.Link(
                        name = "User name",
                        operationId = "getUserByName"
                )
        )
        public Object get() {
            return null;
        }
    }

    static class NoPathClass {
        @GET public Object g() { return null; }
    }

    @Path("/secure")
    @SecuritySchemes({
            @SecurityScheme(
                    securitySchemeName = "petsApiKey",
                    type = SecuritySchemeType.APIKEY,
                    in = SecuritySchemeIn.HEADER,
                    apiKeyName = "api_key"
            ),
            @SecurityScheme(
                    securitySchemeName = "petsOAuth2",
                    type = SecuritySchemeType.OAUTH2,
                    flows = @OAuthFlows(
                            implicit = @OAuthFlow(
                                    authorizationUrl = "https://example.com/api/oauth/dialog",
                                    scopes = @OAuthScope(name = "write:pets", description = "modify pets")
                            )
                    )
            )
    })
    @SecurityRequirement(name = "petsOAuth2", scopes = {"write:pets"})
    static class SecurityResource {
        @DELETE
        @Path("/{id}")
        @SecurityRequirement(name = "petsApiKey")
        public Object delete(@PathParam("id") String id) {
            return null;
        }
    }

    @Path("/response-schema")
    static class ExtensionAndResponseSchemaResource {
        @POST
        @Path("/{id}")
        @Produces("application/json")
        @Operation(extensions = @Extension(name = "x-operation-ext", value = "test-operation-ext"))
        @APIResponseSchema(value = Payload.class, responseCode = "204", responseDescription = "No content")
        public Object update(@PathParam("id") String id, Payload payload) {
            return null;
        }
    }

    @Path("/request-schema")
    static class RequestBodySchemaResource {
        @POST
        @Consumes("application/json")
        public Object create(@RequestBodySchema(Payload.class) String csvBody) {
            return null;
        }
    }

    @Path("/ext")
    static class StandaloneExtensionsResource {
        @GET
        @Extensions(@Extension(name = "x-op-ext", value = "ok"))
        public Object get() {
            return null;
        }
    }

    @Path("/responses-ext")
    static class ResponseExtensionsResource {
        @GET
        @Produces("application/json")
        @APIResponses(
                value = @APIResponse(responseCode = "200", description = "OK"),
                extensions = @Extension(name = "x-responses-ext", value = "test-responses-ext")
        )
        public Object get() {
            return null;
        }
    }

    @Path("/response-schema-default")
    static class DefaultResponseSchemaResource {
        @GET
        @Produces("application/json")
        @APIResponseSchema(Payload[].class)
        public Object get() {
            return null;
        }
    }

    @Path("/responses-content-ext")
    static class ResponseContentExtensionResource {
        @GET
        @Produces("application/json")
        @APIResponse(
                responseCode = "503",
                description = "service not available",
                content = @Content(
                        extensions = @Extension(name = "x-notavailable-ext", value = "true")
                )
        )
        public Object get() {
            return null;
        }
    }

    @Path("/validation")
    static class BeanValidationParameterResource {
        @POST
        @Path("/{test}")
        public Object create(
                @PathParam("test")
                @jakarta.validation.constraints.Size(max = 6)
                @jakarta.validation.constraints.NotNull
                String test) {
            return null;
        }
    }

    static class Payload {
        public String name;
    }

    @Test
    void mapsHeaderExampleAndExamples() {
        // MP OpenAPI 4.2 (#697): @Header.example / @Header.examples (TCK shape: X-Password-Strength).
        AnnotationScanner scanner = new AnnotationScanner(ScanConfig.defaultConfig());
        OpenAPI model = scanner.scanClasses(List.of(HeaderExampleResource.class));

        var header = model.getPaths().getPathItem("/hdr").getGET()
                .getResponses().getAPIResponse("200").getHeaders().get("X-Password-Strength");
        assertEquals("0", header.getExample());
        assertEquals("Strong", header.getExamples().get("strong").getSummary());
        assertEquals("10", header.getExamples().get("strong").getValue());
        assertEquals("5.1", header.getExamples().get("weak").getValue());
    }

    @Test
    void mapsMethodLevelExternalDocumentationOntoTheOperation() {
        // MP OpenAPI 4.2: @ExternalDocumentation on a resource method populates operation.externalDocs.
        AnnotationScanner scanner = new AnnotationScanner(ScanConfig.defaultConfig());
        OpenAPI openAPI = scanner.scanClasses(List.of(ExternalDocsResource.class));

        var docs = openAPI.getPaths().getPathItem("/a").getGET().getExternalDocs();
        assertNotNull(docs);
        assertEquals("https://example.org/AResource.java", docs.getUrl());
        assertEquals("Find more information about this application resource", docs.getDescription());
        assertNull(openAPI.getExternalDocs(), "method-level docs must not leak to the document root");
    }

    @Path("/a")
    static class ExternalDocsResource {
        @GET
        @ExternalDocumentation(description = "Find more information about this application resource",
                url = "https://example.org/AResource.java")
        public String get() {
            return "";
        }
    }

    @Path("/hdr")
    static class HeaderExampleResource {
        @GET
        @APIResponse(responseCode = "200", description = "ok", headers = @Header(name = "X-Password-Strength",
                example = "0",
                examples = {
                    @ExampleObject(name = "strong", summary = "Strong", value = "10"),
                    @ExampleObject(name = "weak", value = "5.1")}))
        public String get() {
            return "";
        }
    }
}


