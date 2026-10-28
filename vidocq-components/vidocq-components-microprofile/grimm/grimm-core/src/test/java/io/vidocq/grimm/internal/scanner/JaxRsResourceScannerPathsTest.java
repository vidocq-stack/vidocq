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
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HEAD;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.OPTIONS;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.eclipse.microprofile.openapi.annotations.tags.Tags;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.PathItem;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Path composition, HTTP verbs, operation merging and tags of {@link JaxRsResourceScanner}
 * (MP OpenAPI 4.2 §5.1 "JAX-RS annotations", §5.2 {@code @Operation}, {@code @Tag}).
 */
class JaxRsResourceScannerPathsTest {

    private static final String JSON = "application/json";
    private static final String MERGE_PATH = "/merge";
    private static final String METHOD_REF = "methodRef";
    private static final String XML = "application/xml";
    private static final String ITEMS = "/items";
    private static final String DONE = "done";

    private static OpenAPI scan(Class<?>... classes) {
        return new AnnotationScanner(ScanConfig.defaultConfig()).scanClasses(List.of(classes));
    }

    /** An HTTP method designator unknown to OpenAPI (WebDAV). */
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @HttpMethod("LOCK")
    @interface Lock {
    }

    @Path("/client")
    interface ClientApi {
        @GET
        String get();
    }

    @Path("trailing/")
    static class TrailingSlashResource {
        @GET
        @Path("/")
        public String root() {
            return DONE;
        }

        @GET
        @Path("child")
        public String child() {
            return DONE;
        }

        public String notAnEndpoint() {
            return DONE;
        }
    }

    @Path("")
    static class EmptyPathResource {
        @GET
        @Path("items")
        public String items() {
            return DONE;
        }

        @Lock
        @Path("locked")
        public String lock() {
            return DONE;
        }
    }

    @Path("/verbs")
    static class OtherVerbsResource {
        @PATCH
        public String patch() {
            return DONE;
        }

        @HEAD
        public void head() {
            // HEAD carries no entity.
        }

        @OPTIONS
        public String options() {
            return DONE;
        }
    }

    @Path(MERGE_PATH)
    @Tag(name = "classTag")
    static class MergedResource {
        @POST
        @Consumes(JSON)
        @Operation(summary = "Create", description = "Creates from JSON")
        @APIResponse(responseCode = "201", description = "Created")
        @Tag(name = "json")
        public String fromJson(@QueryParam("dryRun") boolean dryRun, String body) {
            return body;
        }

        @POST
        @Consumes(XML)
        @Operation(operationId = "createXml")
        @APIResponse(responseCode = "201", description = "Created again")
        @APIResponse(responseCode = "415", description = "Unsupported")
        @Tag(name = "xml")
        @Tag(name = "json")
        public String fromXml(@QueryParam("dryRun") boolean dryRun, @QueryParam("strict") boolean strict,
                              @RequestBody(description = "The XML document") String body) {
            return body;
        }
    }

    @Path("/tags")
    @Tags(value = @Tag(name = "fromContainer"), refs = "classRef")
    static class TagsResource {
        @GET
        public String inherited() {
            return DONE;
        }

        @GET
        @Path("own")
        @Tags(refs = {METHOD_REF, METHOD_REF})
        public String own() {
            return DONE;
        }

        @GET
        @Path("ref")
        @Tag(ref = "byRef")
        @Tag(name = "")
        public String byRef() {
            return DONE;
        }
    }

    @Test
    void interfacesAreNotServerEndpoints() {
        // §5.1: client interfaces (MP Rest Client) are not resources of the application.
        OpenAPI model = scan(ClientApi.class);

        assertNull(model.getPaths().getPathItem("/client"));
    }

    @Test
    void trailingSlashesAndRelativeSegmentsAreNormalized() {
        // §5.1: @Path values are relative; the class and method segments are joined with one '/'.
        OpenAPI model = scan(TrailingSlashResource.class);

        assertNotNull(model.getPaths().getPathItem("/trailing").getGET());
        assertNotNull(model.getPaths().getPathItem("/trailing/child").getGET());
        assertEquals(2, model.getPaths().getPathItems().size());
    }

    @Test
    void emptyClassPathIsTheRootAndUnknownVerbsAreIgnored() {
        OpenAPI model = scan(EmptyPathResource.class);

        assertNotNull(model.getPaths().getPathItem(ITEMS).getGET());
        PathItem locked = model.getPaths().getPathItem("/locked");
        assertNotNull(locked);
        assertTrue(locked.getOperations().isEmpty());
    }

    @Test
    void patchHeadAndOptionsOperationsAreMapped() {
        PathItem item = scan(OtherVerbsResource.class).getPaths().getPathItem("/verbs");

        assertNotNull(item.getPATCH());
        assertNotNull(item.getHEAD());
        assertNotNull(item.getOPTIONS());
        assertNull(item.getGET());
        // §5.2.4: a void method answers with an empty 200 by default.
        assertNull(item.getHEAD().getResponses().getAPIResponse("200").getContent());
    }

    @Test
    void twoMethodsOnTheSamePathAndVerbAreMergedIntoOneOperation() {
        var op = scan(MergedResource.class).getPaths().getPathItem(MERGE_PATH).getPOST();

        assertEquals("Create", op.getSummary());
        assertEquals("Creates from JSON", op.getDescription());
        assertEquals("createXml", op.getOperationId());
        assertEquals(java.util.Set.of("json", "xml"), java.util.Set.copyOf(op.getTags()));
        assertEquals(2, op.getTags().size());
        assertNotNull(op.getRequestBody().getContent().getMediaType(JSON));
        assertNotNull(op.getRequestBody().getContent().getMediaType(XML));
        assertEquals("The XML document", op.getRequestBody().getDescription());
        assertTrue(op.getResponses().getAPIResponse("201").getDescription().startsWith("Created"));
        assertNotNull(op.getResponses().getAPIResponse("415"));
        assertEquals(2, op.getParameters().size());
    }

    @Test
    void classTagsApplyOnlyWhenTheMethodDeclaresNone() {
        // §5.2 @Tag/@Tags: a method-level declaration replaces the class-level ones.
        PathItem inherited = scan(TagsResource.class).getPaths().getPathItem("/tags");
        PathItem own = scan(TagsResource.class).getPaths().getPathItem("/tags/own");
        PathItem byRef = scan(TagsResource.class).getPaths().getPathItem("/tags/ref");

        assertEquals(List.of("fromContainer", "classRef"), inherited.getGET().getTags());
        assertEquals(List.of(METHOD_REF), own.getGET().getTags());
        assertEquals(List.of("byRef"), byRef.getGET().getTags());
    }

    @Test
    void theScannerWithoutSchemaGeneratorStillBuildsOperations() {
        // Package-private constructor used when no schema registry is wired.
        OpenAPI model = org.eclipse.microprofile.openapi.OASFactory.createObject(OpenAPI.class);

        new JaxRsResourceScanner().scan(MergedResource.class, model);

        var op = model.getPaths().getPathItem(MERGE_PATH).getPOST();
        assertNull(op.getRequestBody().getContent().getMediaType(JSON).getSchema());
        assertNull(op.getParameters().get(0).getSchema());
    }

    @Path(ITEMS)
    @Produces(JSON)
    static class ProducesResource {
        @GET
        public List<String> list() {
            return List.of(DONE);
        }
    }

    @Test
    void inferredResponseHasNoSchemaWithoutGenerator() {
        OpenAPI model = org.eclipse.microprofile.openapi.OASFactory.createObject(OpenAPI.class);

        new JaxRsResourceScanner().scan(ProducesResource.class, model);

        var ok = model.getPaths().getPathItem(ITEMS).getGET().getResponses().getAPIResponse("200");
        assertNotNull(ok.getContent().getMediaType(JSON));
        assertNull(ok.getContent().getMediaType(JSON).getSchema());
    }
}
