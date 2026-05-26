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
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.callbacks.Callback;
import org.eclipse.microprofile.openapi.annotations.enums.ParameterIn;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
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
    void operationDeprecatedAndTagsApplied() {
        // Spec §3.6: deprecated and tags on @Operation are applied; missing operationId derived from method name.
        AnnotationScanner scanner = new AnnotationScanner(ScanConfig.defaultConfig());
        OpenAPI model = scanner.scanClasses(List.of(DeprecatedTaggedResource.class));

        var op = model.getPaths().getPathItem("/deprecated").getGET();
        assertEquals(Boolean.TRUE, op.getDeprecated());
        assertTrue(op.getTags() != null && op.getTags().contains("legacy"));
    }

    @Test
    void resourceWithoutPathIsIgnored() {
        // Spec §3.6: a class without @Path produces no path items.
        AnnotationScanner scanner = new AnnotationScanner(ScanConfig.defaultConfig());
        OpenAPI model = scanner.scanClasses(List.of(NoPathClass.class));

        assertTrue(model.getPaths() == null || model.getPaths().getPathItems() == null || model.getPaths().getPathItems().isEmpty());
    }

    // ---------- Fixtures ----------

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

    @Path("/deprecated")
    static class DeprecatedTaggedResource {
        @GET
        @Operation(deprecated = true)
        @org.eclipse.microprofile.openapi.annotations.tags.Tag(name = "legacy")
        public Object g() { return null; }
    }

    static class NoPathClass {
        @GET public Object g() { return null; }
    }

    static class Payload {
        public String name;
    }
}


