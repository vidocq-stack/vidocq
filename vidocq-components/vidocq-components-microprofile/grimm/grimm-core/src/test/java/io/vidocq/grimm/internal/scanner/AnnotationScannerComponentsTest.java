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
import org.eclipse.microprofile.openapi.annotations.Components;
import org.eclipse.microprofile.openapi.annotations.OpenAPIDefinition;
import org.eclipse.microprofile.openapi.annotations.callbacks.Callback;
import org.eclipse.microprofile.openapi.annotations.callbacks.CallbackOperation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeIn;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType;
import org.eclipse.microprofile.openapi.annotations.extensions.Extension;
import org.eclipse.microprofile.openapi.annotations.headers.Header;
import org.eclipse.microprofile.openapi.annotations.info.Info;
import org.eclipse.microprofile.openapi.annotations.links.Link;
import org.eclipse.microprofile.openapi.annotations.links.LinkParameter;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Encoding;
import org.eclipse.microprofile.openapi.annotations.media.ExampleObject;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code @OpenAPIDefinition.components} (MP OpenAPI 4.2 §5.1, Components object): every
 * reusable component kind declared through annotations reaches {@code components}.
 */
class AnnotationScannerComponentsTest {

    private static final String X_KIND = "x-kind";
    private static final String BASIC = "basic";
    private static final String EMPTY_BODY = "EmptyBody";
    private static final String LIMIT = "limit";
    private static final String PLAIN = "plain";
    private static final String TOKEN = "Token";
    private static final String X_RATE = "X-Rate";
    private static final String X_TRACE = "X-Trace";
    private static final String JSON = "application/json";
    private static final String NAMELESS = "nameless, ignored";

    /** A POJO used as a schema implementation. */
    static final class Pet {
        private String name;

        String getName() {
            return name;
        }
    }

    @OpenAPIDefinition(info = @Info(title = "Components", version = "1"), components = @Components(
            schemas = {
                    @Schema(name = "PetRef", ref = "#/components/schemas/Pet"),
                    @Schema(implementation = Pet.class),
                    @Schema(name = TOKEN, type = SchemaType.STRING, format = "uuid", description = "A token"),
                    @Schema(name = "Hidden", hidden = true),
                    @Schema(description = NAMELESS)
            },
            responses = {
                    @APIResponse(name = "NotFound", description = "Not found", ref = "#/components/responses/nf",
                            content = {
                                    @Content(mediaType = JSON, example = "{}",
                                            schema = @Schema(type = SchemaType.OBJECT),
                                            extensions = @Extension(name = X_KIND, value = "media")),
                                    @Content(example = PLAIN)
                            },
                            extensions = @Extension(name = X_KIND, value = "response")),
                    @APIResponse(name = "Bare"),
                    @APIResponse(description = NAMELESS)
            },
            parameters = {
                    @Parameter(name = LIMIT, ref = "#/components/parameters/limit", description = "Page size",
                            required = true, schema = @Schema(type = SchemaType.INTEGER),
                            content = {@Content(mediaType = JSON, example = "10",
                                    schema = @Schema(format = "int32")), @Content},
                            extensions = @Extension(name = X_KIND, value = "parameter")),
                    @Parameter(name = PLAIN),
                    @Parameter(description = NAMELESS)
            },
            examples = {
                    @ExampleObject(name = "sample", summary = "A sample", value = "42"),
                    @ExampleObject(summary = NAMELESS)
            },
            requestBodies = {
                    @RequestBody(name = "PetBody", description = "A pet", required = true,
                            ref = "#/components/requestBodies/pet",
                            content = @Content(mediaType = "multipart/form-data", example = "form",
                                    schema = @Schema(title = "Upload"),
                                    encoding = {
                                            @Encoding(name = "file", contentType = "image/png", explode = true,
                                                    headers = {
                                                            @Header(name = X_RATE, description = "Rate",
                                                                    schema = @Schema(type = SchemaType.INTEGER)),
                                                            @Header(ref = "#/components/headers/X-Trace"),
                                                            @Header(ref = "Plain"),
                                                            @Header(description = NAMELESS)
                                                    }),
                                            @Encoding(name = "meta"),
                                            @Encoding(contentType = NAMELESS)
                                    }),
                            extensions = @Extension(name = X_KIND, value = "body")),
                    @RequestBody(name = "OptionalBody", required = false, content = @Content),
                    @RequestBody(name = EMPTY_BODY, required = false),
                    @RequestBody(name = "DefaultBody"),
                    @RequestBody(description = NAMELESS)
            },
            headers = {
                    @Header(name = X_TRACE, ref = "#/components/headers/trace", description = "Trace id",
                            required = true, deprecated = true,
                            schema = @Schema(type = SchemaType.STRING),
                            extensions = @Extension(name = X_KIND, value = "header")),
                    @Header(name = "X-Plain"),
                    @Header(description = NAMELESS)
            },
            securitySchemes = {
                    @SecurityScheme(securitySchemeName = "apiKey", type = SecuritySchemeType.APIKEY,
                            in = SecuritySchemeIn.HEADER, apiKeyName = "X-Key", description = "Key",
                            ref = "#/components/securitySchemes/key",
                            extensions = @Extension(name = X_KIND, value = "scheme")),
                    @SecurityScheme(securitySchemeName = BASIC, type = SecuritySchemeType.HTTP, scheme = BASIC),
                    @SecurityScheme(securitySchemeName = "untyped"),
                    @SecurityScheme(description = NAMELESS)
            },
            links = {
                    @Link(name = "self", ref = "#/components/links/self", description = "Self link",
                            operationId = "getPet",
                            parameters = {
                                    @LinkParameter(name = "id", expression = "$response.body#/id"),
                                    @LinkParameter(name = "noExpression"),
                                    @LinkParameter(expression = "$request.path.id")
                            },
                            extensions = @Extension(name = X_KIND, value = "link")),
                    @Link(name = "bareLink"),
                    @Link(description = NAMELESS)
            },
            callbacks = {
                    @Callback(name = "onEvent", ref = "#/components/callbacks/event",
                            callbackUrlExpression = "{$request.body#/url}",
                            operations = {
                                    @CallbackOperation(method = "post", summary = "Event", description = "An event",
                                            responses = {
                                                    @APIResponse(responseCode = "204", description = "Received",
                                                            ref = "#/components/responses/received"),
                                                    @APIResponse
                                            }),
                                    @CallbackOperation(method = "get")
                            },
                            extensions = @Extension(name = X_KIND, value = "callback")),
                    @Callback(name = "byRef", callbackUrlExpression = "{$request.query.hook}",
                            pathItemRef = "#/components/pathItems/hook"),
                    @Callback(name = "urlOnly", callbackUrlExpression = "{$request.query.other}"),
                    @Callback(callbackUrlExpression = NAMELESS)
            },
            extensions = @Extension(name = X_KIND, value = "components")))
    static final class AllComponents {
    }

    @OpenAPIDefinition(info = @Info(title = "Empty components", version = "1"))
    static final class NoComponents {
    }

    private static org.eclipse.microprofile.openapi.models.Components components() {
        OpenAPI model = new AnnotationScanner(ScanConfig.defaultConfig()).scanClasses(List.of(AllComponents.class));
        return model.getComponents();
    }

    @Test
    void schemasAreNamedFromTheAnnotationOrTheImplementation() {
        var schemas = components().getSchemas();

        assertEquals("#/components/schemas/Pet", schemas.get("PetRef").getRef());
        assertNotNull(schemas.get("Pet"));
        assertEquals("uuid", schemas.get(TOKEN).getFormat());
        assertEquals("A token", schemas.get(TOKEN).getDescription());
        assertFalse(schemas.containsKey("Hidden"));
    }

    @Test
    void responsesCarryContentAndExtensions() {
        var responses = components().getResponses();

        assertEquals(2, responses.size());
        var notFound = responses.get("NotFound");
        assertEquals("Not found", notFound.getDescription());
        assertEquals("#/components/responses/nf", notFound.getRef());
        assertEquals("response", notFound.getExtensions().get(X_KIND));
        var json = notFound.getContent().getMediaType(JSON);
        assertEquals("{}", json.getExample());
        assertNotNull(json.getSchema());
        assertEquals("media", json.getExtensions().get(X_KIND));
        var any = notFound.getContent().getMediaType("*/*");
        assertEquals(PLAIN, any.getExample());
        assertNull(any.getSchema());
        var bare = responses.get("Bare");
        assertNull(bare.getDescription());
        assertNull(bare.getContent());
    }

    @Test
    void parametersCarrySchemaAndContent() {
        var parameters = components().getParameters();

        assertEquals(2, parameters.size());
        var limit = parameters.get(LIMIT);
        assertEquals(LIMIT, limit.getName());
        assertEquals("#/components/parameters/limit", limit.getRef());
        assertEquals("Page size", limit.getDescription());
        assertEquals(Boolean.TRUE, limit.getRequired());
        assertNotNull(limit.getSchema());
        assertEquals("parameter", limit.getExtensions().get(X_KIND));
        assertEquals("10", limit.getContent().getMediaType(JSON).getExample());
        assertNull(limit.getContent().getMediaType("*/*").getSchema());
        var plain = parameters.get(PLAIN);
        assertNull(plain.getRequired());
        assertNull(plain.getSchema());
        assertNull(plain.getContent());
    }

    @Test
    void examplesAreNamed() {
        var examples = components().getExamples();

        assertEquals(1, examples.size());
        assertEquals("A sample", examples.get("sample").getSummary());
    }

    @Test
    void requestBodiesCarryContentEncodingsAndHeaders() {
        var bodies = components().getRequestBodies();

        assertEquals(4, bodies.size());
        var body = bodies.get("PetBody");
        assertEquals("A pet", body.getDescription());
        assertEquals(Boolean.TRUE, body.getRequired());
        assertEquals("#/components/requestBodies/pet", body.getRef());
        assertEquals("body", body.getExtensions().get(X_KIND));
        var form = body.getContent().getMediaType("multipart/form-data");
        assertEquals("form", form.getExample());
        assertNotNull(form.getSchema());
        assertEquals(2, form.getEncoding().size());
        var file = form.getEncoding().get("file");
        assertEquals("image/png", file.getContentType());
        assertEquals(Boolean.TRUE, file.getExplode());
        assertEquals(3, file.getHeaders().size());
        assertEquals("Rate", file.getHeaders().get(X_RATE).getDescription());
        assertNotNull(file.getHeaders().get(X_RATE).getSchema());
        assertEquals("#/components/headers/X-Trace", file.getHeaders().get(X_TRACE).getRef());
        // A short reference is expanded to the headers component.
        assertEquals("#/components/headers/Plain", file.getHeaders().get("Plain").getRef());
        var meta = form.getEncoding().get("meta");
        assertNull(meta.getContentType());
        assertNull(meta.getExplode());
    }

    @Test
    void requestBodyRequiredFlagFollowsTheContent() {
        // @RequestBody.required defaults to true (MP OpenAPI 4.2 javadoc), unlike the OpenAPI default.
        var bodies = components().getRequestBodies();

        assertEquals(Boolean.TRUE, bodies.get("DefaultBody").getRequired());
        assertEquals(Boolean.FALSE, bodies.get("OptionalBody").getRequired());
        assertNull(bodies.get(EMPTY_BODY).getRequired());
        assertNull(bodies.get(EMPTY_BODY).getContent());
    }

    @Test
    void headersAreMapped() {
        var headers = components().getHeaders();

        assertEquals(2, headers.size());
        var trace = headers.get(X_TRACE);
        assertEquals("#/components/headers/trace", trace.getRef());
        assertEquals("Trace id", trace.getDescription());
        assertEquals(Boolean.TRUE, trace.getRequired());
        assertEquals(Boolean.TRUE, trace.getDeprecated());
        assertNotNull(trace.getSchema());
        assertEquals("header", trace.getExtensions().get(X_KIND));
        assertNull(headers.get("X-Plain").getDescription());
    }

    @Test
    void securitySchemesAreMapped() {
        var schemes = components().getSecuritySchemes();

        assertEquals(3, schemes.size());
        var apiKey = schemes.get("apiKey");
        assertEquals(org.eclipse.microprofile.openapi.models.security.SecurityScheme.Type.APIKEY, apiKey.getType());
        assertEquals(org.eclipse.microprofile.openapi.models.security.SecurityScheme.In.HEADER, apiKey.getIn());
        assertEquals("X-Key", apiKey.getName());
        assertEquals("Key", apiKey.getDescription());
        assertEquals("#/components/securitySchemes/key", apiKey.getRef());
        assertEquals("scheme", apiKey.getExtensions().get(X_KIND));
        var basic = schemes.get(BASIC);
        assertEquals(org.eclipse.microprofile.openapi.models.security.SecurityScheme.Type.HTTP, basic.getType());
        assertEquals(BASIC, basic.getScheme());
        assertNull(basic.getIn());
        var untyped = schemes.get("untyped");
        assertNull(untyped.getType());
        assertNull(untyped.getName());
    }

    @Test
    void linksKeepOnlyCompleteParameters() {
        var links = components().getLinks();

        assertEquals(2, links.size());
        var self = links.get("self");
        assertEquals("#/components/links/self", self.getRef());
        assertEquals("Self link", self.getDescription());
        assertEquals("getPet", self.getOperationId());
        assertEquals(1, self.getParameters().size());
        assertEquals("$response.body#/id", self.getParameters().get("id"));
        assertEquals("link", self.getExtensions().get(X_KIND));
        var bare = links.get("bareLink");
        assertNull(bare.getOperationId());
        assertNull(bare.getDescription());
    }

    @Test
    void callbacksMapOperationsOrPathItemReferences() {
        var callbacks = components().getCallbacks();

        assertEquals(3, callbacks.size());
        var onEvent = callbacks.get("onEvent");
        assertEquals("#/components/callbacks/event", onEvent.getRef());
        assertEquals("callback", onEvent.getExtensions().get(X_KIND));
        var item = onEvent.getPathItem("{$request.body#/url}");
        var post = item.getPOST();
        assertEquals("Event", post.getSummary());
        assertEquals("An event", post.getDescription());
        assertEquals("Received", post.getResponses().getAPIResponse("204").getDescription());
        assertEquals("#/components/responses/received", post.getResponses().getAPIResponse("204").getRef());
        assertNotNull(post.getResponses().getAPIResponse("default"));
        assertNull(item.getGET().getResponses());
        assertEquals("#/components/pathItems/hook",
                callbacks.get("byRef").getPathItem("{$request.query.hook}").getRef());
        var urlOnly = callbacks.get("urlOnly");
        assertTrue(urlOnly.getPathItems() == null || urlOnly.getPathItems().isEmpty());
    }

    @Test
    void componentExtensionsAreApplied() {
        assertEquals("components", components().getExtensions().get(X_KIND));
    }

    @Test
    void definitionWithoutComponentsDeclaresNone() {
        OpenAPI model = new AnnotationScanner(ScanConfig.defaultConfig()).scanClasses(List.of(NoComponents.class));
        var components = model.getComponents();

        assertTrue(components == null || components.getSchemas() == null || components.getSchemas().isEmpty());
        assertTrue(components == null || components.getResponses() == null);
    }
}
