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
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Encoding;
import org.eclipse.microprofile.openapi.annotations.media.ExampleObject;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.headers.Header;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBodySchema;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Request body mapping of {@link JaxRsResourceScanner} (MP OpenAPI 4.2 §5.2 {@code @RequestBody},
 * {@code @RequestBodySchema}, {@code @Content}, {@code @Encoding}; JAX-RS entity and form
 * parameters §5.1).
 */
class JaxRsResourceScannerRequestBodyTest {

    private static final String FORM = "application/x-www-form-urlencoded";
    private static final String FORMS_PATH = "/forms";
    private static final String X_RATE = "X-Rate";
    private static final String MULTIPART = "multipart/form-data";
    private static final String JSON = "application/json";
    private static final String DONE = "done";

    private static OpenAPI scan(Class<?> resource) {
        return new AnnotationScanner(ScanConfig.defaultConfig()).scanClasses(List.of(resource));
    }

    private static org.eclipse.microprofile.openapi.models.parameters.RequestBody bodyOf(OpenAPI model, String path) {
        return model.getPaths().getPathItem(path).getPOST().getRequestBody();
    }

    public static final class Pet {
        public String name;
    }

    @Path(FORMS_PATH)
    static class FormResource {
        @POST
        public String login(@FormParam("user") String user, @FormParam("") String unnamed,
                            @FormParam("age") int age) {
            return user + unnamed + age;
        }

        @POST
        @Path("multipart")
        @Consumes(MULTIPART)
        public String upload(@FormParam("file") String file) {
            return file;
        }
    }

    @Test
    void formParametersBecomeAnObjectSchema() {
        // §5.1: @FormParam parameters are properties of a form-urlencoded request body.
        OpenAPI model = scan(FormResource.class);

        var form = bodyOf(model, FORMS_PATH).getContent().getMediaType(FORM).getSchema();
        assertEquals(List.of(org.eclipse.microprofile.openapi.models.media.Schema.SchemaType.OBJECT), form.getType());
        assertEquals(java.util.Set.of("user", "age"), form.getProperties().keySet());
        var multipart = bodyOf(model, "/forms/multipart").getContent();
        assertNotNull(multipart.getMediaType(MULTIPART).getSchema().getProperties().get("file"));
    }

    @Test
    void formBodyWithoutSchemaGenerator() {
        OpenAPI model = OASFactory.createObject(OpenAPI.class);

        new JaxRsResourceScanner().scan(FormResource.class, model);

        assertNull(bodyOf(model, FORMS_PATH).getContent().getMediaType(FORM).getSchema());
    }

    @Path("/bodies")
    static class BodiesResource {
        @POST
        @Path("schema")
        public String bySchema(@RequestBodySchema(Pet.class) Object body) {
            return DONE;
        }

        @POST
        @Path("explicit")
        public String explicit(@RequestBody(description = "A pet", required = true, ref = "#/components/requestBodies/Pet",
                content = @Content(examples = @ExampleObject(name = "rex", value = "{\"name\":\"rex\"}"))) Pet pet) {
            return DONE;
        }

        @POST
        @Path("explicit-with-schema")
        public String explicitWithSchema(
                @RequestBodySchema(Pet.class)
                @RequestBody(content = @Content(mediaType = JSON)) Object pet) {
            return DONE;
        }

        @POST
        @Path("optional")
        @RequestBody(description = "Optional body", required = false)
        public String optional(@PathParam("x") String x, Pet pet) {
            return DONE;
        }

        @POST
        @Path("hidden")
        public String hidden(@Schema(hidden = true) Pet pet) {
            return DONE;
        }

        @POST
        @Path("site-schema")
        public String siteSchema(@Schema(description = "The pet to store") Pet pet) {
            return DONE;
        }

        @POST
        @Path("method-level")
        @RequestBody(content = @Content(mediaType = JSON, schema = @Schema(type = SchemaType.STRING)))
        public String methodLevel() {
            return DONE;
        }
    }

    @Test
    void requestBodySchemaOverridesTheEntityType() {
        // §5.2 @RequestBodySchema: the class to publish as schema of the body.
        var body = bodyOf(scan(BodiesResource.class), "/bodies/schema");

        assertNotNull(body.getContent().getMediaType(JSON).getSchema());
        assertEquals(Boolean.TRUE, body.getRequired());
    }

    @Test
    void explicitRequestBodyAttributesAreCopied() {
        OpenAPI model = scan(BodiesResource.class);
        var explicit = bodyOf(model, "/bodies/explicit");

        assertEquals("A pet", explicit.getDescription());
        assertEquals(Boolean.TRUE, explicit.getRequired());
        assertEquals("#/components/requestBodies/Pet", explicit.getRef());
        var any = explicit.getContent().getMediaType("*/*");
        assertNotNull(any.getExamples().get("rex"));
        assertNotNull(any.getSchema());

        var withSchema = bodyOf(model, "/bodies/explicit-with-schema").getContent().getMediaType(JSON);
        assertNotNull(withSchema.getSchema());
    }

    @Test
    void explicitRequiredFlagOfTheRequestBody() {
        // §5.2 @RequestBody: required = false is published as such; the annotation default is true.
        OpenAPI model = scan(BodiesResource.class);

        var optional = bodyOf(model, "/bodies/optional");
        assertEquals(Boolean.FALSE, optional.getRequired());
        assertEquals("Optional body", optional.getDescription());

        var methodLevel = bodyOf(model, "/bodies/method-level");
        assertEquals(Boolean.TRUE, methodLevel.getRequired());
        assertNotNull(methodLevel.getContent().getMediaType(JSON).getSchema());
    }

    @Test
    void entitySchemaAnnotationIsHonoured() {
        OpenAPI model = scan(BodiesResource.class);

        assertNull(bodyOf(model, "/bodies/hidden").getContent().getMediaType(JSON).getSchema());
        var site = bodyOf(model, "/bodies/site-schema").getContent().getMediaType(JSON).getSchema();
        assertEquals("The pet to store", site.getDescription());
    }

    @Path("/encoded")
    static class EncodingResource {
        @PUT
        public String put(@RequestBody(content = @Content(mediaType = MULTIPART,
                example = "raw",
                encoding = {
                        @Encoding(name = "file", contentType = "image/png", explode = true, allowReserved = true,
                                style = "spaceDelimited",
                                headers = {@Header(name = X_RATE, description = "Rate"), @Header(ref = "#/components/headers/Trace"),
                                        @Header(ref = "Plain"), @Header(description = "nameless")}),
                        @Encoding(name = "meta", style = "bogus"),
                        @Encoding(contentType = "ignored/without-name")
                })) Pet pet) {
            return DONE;
        }
    }

    @Test
    void encodingsOfMultipartContentAreMapped() {
        // §5.2 @Encoding: per-property serialization of multipart / form bodies.
        var mediaType = scan(EncodingResource.class).getPaths().getPathItem("/encoded").getPUT()
                .getRequestBody().getContent().getMediaType(MULTIPART);

        assertEquals("raw", mediaType.getExample());
        assertEquals(java.util.Set.of("file", "meta"), mediaType.getEncoding().keySet());
        var file = mediaType.getEncoding().get("file");
        assertEquals("image/png", file.getContentType());
        assertEquals(Boolean.TRUE, file.getExplode());
        assertEquals(Boolean.TRUE, file.getAllowReserved());
        assertEquals(org.eclipse.microprofile.openapi.models.media.Encoding.Style.SPACE_DELIMITED, file.getStyle());
        assertEquals(java.util.Set.of(X_RATE, "Trace", "Plain"), file.getHeaders().keySet());
        assertEquals("Rate", file.getHeaders().get(X_RATE).getDescription());
        assertEquals("#/components/headers/Trace", file.getHeaders().get("Trace").getRef());
        // An unknown style value is ignored.
        assertNull(mediaType.getEncoding().get("meta").getStyle());
        assertTrue(mediaType.getEncoding().get("meta").getHeaders() == null
                || mediaType.getEncoding().get("meta").getHeaders().isEmpty());
    }
}
