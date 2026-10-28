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
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.headers.Header;
import org.eclipse.microprofile.openapi.annotations.links.Link;
import org.eclipse.microprofile.openapi.annotations.links.LinkParameter;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponseSchema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.servers.Server;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Response mapping of {@link JaxRsResourceScanner} (MP OpenAPI 4.2 §5.2 {@code @APIResponse},
 * {@code @APIResponses}, {@code @APIResponseSchema}, {@code @Header}, {@code @Link}, and the
 * responses of JAX-RS {@code ExceptionMapper}s).
 */
class JaxRsResourceScannerResponsesTest {

    private static final String JSON = "application/json";
    private static final String THE_PET = "The pet";
    private static final String DEFAULT_CODE = "default";
    private static final String X_RATE = "X-Rate";
    private static final String BY_REF = "byRef";
    private static final String SCHEMAS_PATH = "/schemas";
    private static final String TEXT = "text/plain";
    private static final String DONE = "done";
    private static final String OK = "200";

    private static OpenAPI scan(Class<?> resource) {
        return new AnnotationScanner(ScanConfig.defaultConfig()).scanClasses(List.of(resource));
    }

    private static org.eclipse.microprofile.openapi.models.responses.APIResponses responsesOf(
            OpenAPI model, String path) {
        return model.getPaths().getPathItem(path).getGET().getResponses();
    }

    public static final class Pet {
        public String name;
    }

    @Path("/declared")
    @Produces(JSON)
    @APIResponses(value = @APIResponse(responseCode = "500", description = "Server error"),
            extensions = @org.eclipse.microprofile.openapi.annotations.extensions.Extension(name = "x-class", value = "c"))
    @APIResponse(responseCode = "401", description = "Unauthorized")
    static class DeclaredResource {
        @GET
        @APIResponses(value = {
                @APIResponse(responseCode = OK, description = THE_PET,
                        content = {@Content(schema = @Schema(implementation = Pet.class)), @Content(mediaType = TEXT)}),
                @APIResponse(description = "Anything else", ref = "#/components/responses/Default")
        }, extensions = @org.eclipse.microprofile.openapi.annotations.extensions.Extension(name = "x-agg", value = "a"))
        @Operation(deprecated = true)
        public Pet get() {
            return new Pet();
        }
    }

    @Path("/bare")
    static class NoProducesResource {
        @GET
        @APIResponse(responseCode = OK, content = @Content(example = "x"))
        public String noProduces() {
            return DONE;
        }
    }

    @Test
    void declaredResponsesOfClassAndMethodAreCombined() {
        // §5.2 @APIResponse: class-level responses apply to every operation of the resource.
        OpenAPI model = scan(DeclaredResource.class);
        var op = model.getPaths().getPathItem("/declared").getGET();
        var responses = op.getResponses();

        assertEquals(Set.of(OK, DEFAULT_CODE, "401", "500"), responses.getAPIResponses().keySet());
        assertEquals(Boolean.TRUE, op.getDeprecated());
        assertEquals("#/components/responses/Default", responses.getDefaultValue().getRef());
        var ok = responses.getAPIResponse(OK);
        assertNotNull(ok.getContent().getMediaType(JSON).getSchema());
        assertNotNull(ok.getContent().getMediaType(TEXT));
        assertEquals("a", responses.getExtensions().get("x-agg"));
    }

    @Test
    void contentWithoutMediaTypeAndWithoutProducesIsAnyMediaType() {
        var responses = responsesOf(scan(NoProducesResource.class), "/bare");

        assertEquals("x", responses.getAPIResponse(OK).getContent().getMediaType("*/*").getExample());
    }

    @Path("/headers")
    static class HeadersAndLinksResource {
        @GET
        @APIResponse(responseCode = OK, description = "With headers and links",
                headers = {
                        @Header(name = X_RATE, description = "Rate", required = true, deprecated = true,
                                schema = @Schema(type = SchemaType.INTEGER)),
                        @Header(ref = "#/components/headers/Trace"),
                        @Header(description = "nameless")
                },
                links = {
                        @Link(name = "self", operationId = "getPet", description = "Self",
                                requestBody = "$request.body", parameters = {
                                        @LinkParameter(name = "id", expression = "$response.body#/id"),
                                        @LinkParameter(name = "ignored")},
                                server = @Server(url = "https://link.example", description = "Link server")),
                        @Link(name = BY_REF, ref = "#/components/links/ByRef",
                                operationRef = "#/paths/~1pets/get", server = @Server(url = "https://bare.example")),
                        @Link(description = "nameless")
                })
        public String get() {
            return DONE;
        }
    }

    @Test
    void responseHeadersAndLinksAreMapped() {
        var ok = responsesOf(scan(HeadersAndLinksResource.class), "/headers").getAPIResponse(OK);

        assertEquals(Set.of(X_RATE, "Trace"), ok.getHeaders().keySet());
        var rate = ok.getHeaders().get(X_RATE);
        assertEquals(Boolean.TRUE, rate.getRequired());
        assertEquals(Boolean.TRUE, rate.getDeprecated());
        assertNotNull(rate.getSchema());
        assertEquals("#/components/headers/Trace", ok.getHeaders().get("Trace").getRef());

        assertEquals(Set.of("self", BY_REF), ok.getLinks().keySet());
        var self = ok.getLinks().get("self");
        assertEquals("getPet", self.getOperationId());
        assertEquals("Self", self.getDescription());
        assertEquals("$request.body", self.getRequestBody());
        assertEquals(Set.of("id"), self.getParameters().keySet());
        assertEquals("https://link.example", self.getServer().getUrl());
        assertEquals("Link server", self.getServer().getDescription());
        var byRef = ok.getLinks().get(BY_REF);
        assertEquals("#/components/links/ByRef", byRef.getRef());
        assertEquals("#/paths/~1pets/get", byRef.getOperationRef());
        assertNull(byRef.getServer().getDescription());
    }

    @Path(SCHEMAS_PATH)
    @Produces(JSON)
    static class ResponseSchemaResource {
        @POST
        @APIResponseSchema(value = Pet.class, responseCode = "201")
        public Pet create() {
            return new Pet();
        }

        @DELETE
        @APIResponseSchema(Pet.class)
        public void delete() {
            // No entity: the default response code is 204.
        }

        @GET
        @APIResponseSchema(value = Pet.class, responseDescription = THE_PET)
        @APIResponse(responseCode = OK, content = @Content(mediaType = JSON))
        public Pet get() {
            return new Pet();
        }

        @GET
        @Path("c400")
        @APIResponseSchema(value = Pet.class, responseCode = "400")
        public Pet code400() {
            return new Pet();
        }

        @GET
        @Path("c401")
        @APIResponseSchema(value = Pet.class, responseCode = "401")
        public Pet code401() {
            return new Pet();
        }

        @GET
        @Path("c403")
        @APIResponseSchema(value = Pet.class, responseCode = "403")
        public Pet code403() {
            return new Pet();
        }

        @GET
        @Path("c404")
        @APIResponseSchema(value = Pet.class, responseCode = "404")
        public Pet code404() {
            return new Pet();
        }

        @GET
        @Path("c500")
        @APIResponseSchema(value = Pet.class, responseCode = "500")
        public Pet code500() {
            return new Pet();
        }

        @GET
        @Path("c202")
        @APIResponseSchema(value = Pet.class, responseCode = "202")
        public Pet code202() {
            return new Pet();
        }

        @GET
        @Path("cdefault")
        @APIResponseSchema(value = Pet.class, responseCode = DEFAULT_CODE)
        public Pet codeDefault() {
            return new Pet();
        }

        @GET
        @Path("c299")
        @APIResponseSchema(value = Pet.class, responseCode = "299")
        public Pet code299() {
            return new Pet();
        }
    }

    @Test
    void responseSchemaUsesTheDefaultCodeAndDescription() {
        // §5.2 @APIResponseSchema: 200 (or 204 for void) unless responseCode is given.
        OpenAPI model = scan(ResponseSchemaResource.class);
        var item = model.getPaths().getPathItem(SCHEMAS_PATH);

        var created = item.getPOST().getResponses().getAPIResponse("201");
        assertEquals("Created", created.getDescription());
        assertNotNull(created.getContent().getMediaType(JSON).getSchema());
        assertEquals("No Content", item.getDELETE().getResponses().getAPIResponse("204").getDescription());
        var ok = item.getGET().getResponses().getAPIResponse(OK);
        assertEquals(THE_PET, ok.getDescription());
        assertNotNull(ok.getContent().getMediaType(JSON).getSchema());
    }

    @Test
    void responseSchemaDefaultDescriptionsFollowTheStatusCode() {
        OpenAPI model = scan(ResponseSchemaResource.class);

        assertEquals("Bad Request", schemaResponseDescription(model, "400"));
        assertEquals("Unauthorized", schemaResponseDescription(model, "401"));
        assertEquals("Forbidden", schemaResponseDescription(model, "403"));
        assertEquals("Not Found", schemaResponseDescription(model, "404"));
        assertEquals("Internal Server Error", schemaResponseDescription(model, "500"));
        assertEquals("Accepted", schemaResponseDescription(model, "202"));
        assertEquals("Default response", schemaResponseDescription(model, DEFAULT_CODE));
        assertEquals("Response 299", schemaResponseDescription(model, "299"));
    }

    private static String schemaResponseDescription(OpenAPI model, String code) {
        return model.getPaths().getPathItem("/schemas/c" + code).getGET().getResponses().getAPIResponse(code)
                .getDescription();
    }

    @Test
    void responseSchemaIsIgnoredWithoutSchemaGenerator() {
        OpenAPI model = OASFactory.createObject(OpenAPI.class);

        new JaxRsResourceScanner().scan(ResponseSchemaResource.class, model);

        assertNull(model.getPaths().getPathItem(SCHEMAS_PATH).getPOST().getResponses().getAPIResponse("201"));
    }

    @Path("/mapped")
    @Produces(JSON)
    static class MappedResource {
        @GET
        public String get() throws ScannerFixtureException, IOException {
            return DONE;
        }

        @GET
        @Path("unmapped")
        public String unmapped() throws ScannerFixtureUnmappedException {
            return DONE;
        }
    }

    @Test
    void exceptionMapperResponsesDocumentTheOperation() {
        // MP OpenAPI 4.2: @APIResponse on an ExceptionMapper documents the methods throwing its exception.
        var responses = responsesOf(scan(MappedResource.class), "/mapped");

        assertEquals(Set.of(OK, "409", DEFAULT_CODE), responses.getAPIResponses().keySet());
        assertEquals("OK", responses.getAPIResponse(OK).getDescription());
        var conflict = responses.getAPIResponse("409");
        assertEquals("Conflict", conflict.getDescription());
        assertEquals("#/components/responses/Conflict", conflict.getRef());
        assertEquals("{}", conflict.getContent().getMediaType(JSON).getExample());
        assertNotNull(conflict.getHeaders().get("X-Reason"));
        var mapped = responses.getAPIResponse(DEFAULT_CODE);
        assertEquals("Mapped failure", mapped.getDescription());
        assertNotNull(mapped.getContent().getMediaType(TEXT));
    }

    @Test
    void aMapperNamedClassThatIsNotAnExceptionMapperIsIgnored() {
        var responses = responsesOf(scan(MappedResource.class), "/mapped/unmapped");

        assertEquals(Set.of(OK), responses.getAPIResponses().keySet());
        assertTrue(responses.getAPIResponse(OK).getContent().getMediaTypes().containsKey(JSON));
    }
}
