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
package io.vidocq.grimm.internal.serialization;

import org.eclipse.microprofile.openapi.models.Components;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.Operation;
import org.eclipse.microprofile.openapi.models.PathItem;
import org.eclipse.microprofile.openapi.models.callbacks.Callback;
import org.eclipse.microprofile.openapi.models.examples.Example;
import org.eclipse.microprofile.openapi.models.headers.Header;
import org.eclipse.microprofile.openapi.models.info.Info;
import org.eclipse.microprofile.openapi.models.links.Link;
import org.eclipse.microprofile.openapi.models.media.Encoding;
import org.eclipse.microprofile.openapi.models.media.MediaType;
import org.eclipse.microprofile.openapi.models.parameters.Parameter;
import org.eclipse.microprofile.openapi.models.parameters.RequestBody;
import org.eclipse.microprofile.openapi.models.responses.APIResponse;
import org.eclipse.microprofile.openapi.models.security.SecurityScheme;
import org.eclipse.microprofile.openapi.models.servers.Server;
import org.eclipse.microprofile.openapi.models.servers.ServerVariable;
import org.eclipse.microprofile.openapi.models.tags.Tag;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Static-file mapping of every OpenAPI 3.1 object (spec §4.2, model §3.1): each standard field
 * lands on its typed property, {@code x-} keys become extensions, unknown keys and values of the
 * wrong JSON type are dropped.
 */
class StaticDocumentMappingTest {

    private static final String PETS = "/pets";
    private static final String BEARER = "bearer";
    private static final String JSON_MEDIA = "application/json";

    static final String DOCUMENT = """
            {
              "openapi": "3.1.0", "x-top": "t", "unknownTop": 1,
              "info": {"title": "T", "version": "1", "summary": "S", "description": "D",
                "termsOfService": "https://tos", "x-i": 1, "other": 1,
                "contact": {"name": "N", "url": "https://contact", "email": "e@x", "x-c": 1, "other": 1},
                "license": {"name": "Apache", "url": "https://lic", "identifier": "Apache-2.0", "x-l": 1, "other": 1}},
              "externalDocs": {"url": "https://docs", "description": "docs", "x-d": 1, "other": 1},
              "servers": [
                {"url": "https://{env}.example.com", "description": "srv", "x-s": 1, "other": 1,
                 "variables": {"env": {"description": "environment", "default": "prod", "enum": ["prod", "dev"],
                                       "x-v": 1, "other": 1}, "bad": 1}},
                1],
              "security": [{"oauth": ["read", "write"], "key": [], "weird": 1}, 1],
              "tags": [{"name": "pets", "description": "Pets", "externalDocs": {"url": "https://tags"},
                        "x-t": 1, "other": 1}, 1],
              "paths": {
                "x-p": 1,
                "/pets": {
                  "summary": "ps", "description": "pd", "x-pi": 1, "other": 1,
                  "servers": [{"url": "https://path"}],
                  "parameters": [{"name": "trace", "in": "header"}, 1],
                  "get": {
                    "operationId": "listPets", "summary": "s", "description": "d", "tags": ["pets"],
                    "deprecated": true, "externalDocs": {"url": "https://op"}, "servers": [{"url": "https://op"}],
                    "security": [{"oauth": ["read"]}], "x-op": 1, "other": 1,
                    "parameters": [
                      {"name": "limit", "in": "query", "required": false, "description": "max", "deprecated": "true",
                       "allowEmptyValue": true, "style": "form", "explode": true, "allowReserved": false,
                       "schema": {"type": "integer"}, "example": 5, "examples": {"five": {"value": 5}},
                       "x-pa": 1, "other": 1},
                      {"name": "id", "in": "path", "style": "matrix"},
                      {"in": "cookie", "style": "label"},
                      {"in": "nowhere", "style": "simple"},
                      {"in": null, "style": "spaceDelimited"},
                      {"style": "pipeDelimited"}, {"style": "deepObject"}, {"style": "bogus"}, {"style": null},
                      {"$ref": "#/components/parameters/p", "content": {"text/plain": {}}}
                    ],
                    "requestBody": {"description": "body", "required": true, "x-rb": 1, "other": 1,
                      "content": {"application/json": {
                        "schema": {"$ref": "#/components/schemas/Pet"}, "example": {"a": 1},
                        "examples": {"e": {"summary": "es"}}, "x-m": 1, "other": 1,
                        "encoding": {
                          "file": {"contentType": "image/png", "headers": {"X-Rate": {"description": "rate"}},
                                   "style": "form", "explode": true, "allowReserved": true, "x-e": 1, "other": 1},
                          "a": {"style": "spaceDelimited"}, "b": {"style": "pipeDelimited"},
                          "c": {"style": "deepObject"}, "d": {"style": "bogus"}, "e": {"style": null},
                          "bad": 1}},
                        "x-ignored": {}, "text/plain": 1}},
                    "responses": {
                      "200": {"description": "ok", "x-r": 1, "other": 1,
                        "content": {"application/json": {}},
                        "headers": {
                          "X-H": {"description": "hd", "required": true, "deprecated": false, "allowEmptyValue": false,
                                  "style": "simple", "explode": false, "schema": {"type": "string"}, "example": "x",
                                  "examples": {"ex": {"value": "x"}}, "content": {"text/plain": {}},
                                  "x-h": 1, "other": 1},
                          "X-Form": {"style": "form"},
                          "X-Ref": {"$ref": "#/components/headers/h"},
                          "bad": 1},
                        "links": {
                          "self": {"operationRef": "#/paths/~1pets/get", "operationId": "listPets",
                                   "requestBody": "$request.body", "description": "ld",
                                   "parameters": {"id": "$response.body#/id"}, "server": {"url": "https://link"},
                                   "x-li": 1, "other": 1},
                          "ref": {"$ref": "#/components/links/L"},
                          "flat": {"parameters": 1},
                          "bad": 1}},
                      "default": {"$ref": "#/components/responses/Err"},
                      "x-rs": 1, "bad": 1},
                    "callbacks": {
                      "onEvent": {"{$request.body#/url}": {"post": {"operationId": "cb"}}, "x-cb": 1},
                      "byRef": {"$ref": "#/components/callbacks/cb"},
                      "bad": 1}
                  },
                  "put": {}, "post": {}, "delete": {}, "patch": {}, "head": {}, "options": {}, "trace": {}
                },
                "/ref": {"$ref": "#/components/pathItems/shared"}
              },
              "webhooks": {"newPet": {"post": {"operationId": "hook"}}, "x-w": 1, "bad": 1},
              "components": {
                "x-comp": 1, "other": 1,
                "schemas": {"Pet": {"type": "object"}},
                "responses": {"Err": {"description": "err"}, "bad": 1},
                "parameters": {"p": {"name": "p", "in": "query"}, "bad": 1},
                "examples": {"ex": {"summary": "s", "description": "d", "value": {"v": 1},
                                    "externalValue": "https://ex", "x-ex": 1, "other": 1},
                             "ref": {"$ref": "#/components/examples/ex"}, "bad": 1},
                "requestBodies": {"rb": {"$ref": "#/components/requestBodies/x"}, "bad": 1},
                "headers": {"h": {"description": "component header"}, "bad": 1},
                "links": {"L": {"operationId": "x"}, "bad": 1},
                "callbacks": {"c": {"/x": {}}, "bad": 1},
                "pathItems": {"shared": {"get": {"operationId": "shared"}}, "bad": 1},
                "securitySchemes": {
                  "key": {"type": "apiKey", "name": "X-Key", "in": "header", "description": "api key",
                          "x-ss": 1, "other": 1},
                  "bearer": {"type": "http", "scheme": "bearer", "bearerFormat": "JWT"},
                  "oidc": {"type": "openIdConnect", "openIdConnectUrl": "https://oidc"},
                  "mtls": {"type": "mutualTLS", "in": "query"},
                  "odd": {"type": "bogus", "in": "cookie"},
                  "none": {"type": null, "in": null},
                  "where": {"in": "bogus"},
                  "ref": {"$ref": "#/components/securitySchemes/key"},
                  "bad": 1}
              }
            }
            """;

    private static OpenAPI document() {
        return new JsonDeserializer().deserialize(DOCUMENT);
    }

    private static Operation get() {
        return document().getPaths().getPathItem(PETS).getGET();
    }

    @Test
    void rootInfoAndExternalDocs() {
        OpenAPI openAPI = document();
        Info info = openAPI.getInfo();

        assertEquals("3.1.0", openAPI.getOpenapi());
        assertEquals(Map.of("x-top", "t"), openAPI.getExtensions());
        assertEquals("S", info.getSummary());
        assertEquals("D", info.getDescription());
        assertEquals("https://tos", info.getTermsOfService());
        assertEquals(Map.of("x-i", 1L), info.getExtensions());
        assertEquals("e@x", info.getContact().getEmail());
        assertEquals("https://contact", info.getContact().getUrl());
        assertEquals(Map.of("x-c", 1L), info.getContact().getExtensions());
        assertEquals("Apache-2.0", info.getLicense().getIdentifier());
        assertEquals("https://lic", info.getLicense().getUrl());
        assertEquals(Map.of("x-l", 1L), info.getLicense().getExtensions());
        assertEquals("docs", openAPI.getExternalDocs().getDescription());
        assertEquals(Map.of("x-d", 1L), openAPI.getExternalDocs().getExtensions());
    }

    @Test
    void serversSecurityAndTags() {
        OpenAPI openAPI = document();

        assertEquals(1, openAPI.getServers().size());
        Server server = openAPI.getServers().get(0);
        assertEquals("srv", server.getDescription());
        assertEquals(Map.of("x-s", 1L), server.getExtensions());
        assertEquals(1, server.getVariables().size());
        ServerVariable env = server.getVariables().get("env");
        assertEquals("environment", env.getDescription());
        assertEquals("prod", env.getDefaultValue());
        assertEquals(List.of("prod", "dev"), env.getEnumeration());
        assertEquals(Map.of("x-v", 1L), env.getExtensions());
        assertEquals(1, openAPI.getSecurity().size());
        Map<String, List<String>> schemes = openAPI.getSecurity().get(0).getSchemes();
        assertEquals(List.of("read", "write"), schemes.get("oauth"));
        assertEquals(List.of(), schemes.get("key"));
        assertEquals(2, schemes.size());
        assertEquals(1, openAPI.getTags().size());
        Tag tag = openAPI.getTags().get(0);
        assertEquals("Pets", tag.getDescription());
        assertEquals("https://tags", tag.getExternalDocs().getUrl());
        assertEquals(Map.of("x-t", 1L), tag.getExtensions());
    }

    @Test
    void pathItemsAndWebhooks() {
        OpenAPI openAPI = document();
        PathItem pets = openAPI.getPaths().getPathItem(PETS);

        assertEquals(Map.of("x-p", 1L), openAPI.getPaths().getExtensions());
        assertEquals("ps", pets.getSummary());
        assertEquals("pd", pets.getDescription());
        assertEquals(Map.of("x-pi", 1L), pets.getExtensions());
        assertEquals("https://path", pets.getServers().get(0).getUrl());
        assertEquals(1, pets.getParameters().size());
        assertEquals(8, pets.getOperations().size());
        assertEquals("#/components/pathItems/shared", openAPI.getPaths().getPathItem("/ref").getRef());
        assertEquals("hook", openAPI.getWebhooks().get("newPet").getPOST().getOperationId());
        assertEquals(1, openAPI.getWebhooks().size());
    }

    @Test
    void operationFields() {
        Operation get = get();

        assertEquals("listPets", get.getOperationId());
        assertEquals("s", get.getSummary());
        assertEquals("d", get.getDescription());
        assertEquals(List.of("pets"), get.getTags());
        assertEquals(Boolean.TRUE, get.getDeprecated());
        assertEquals("https://op", get.getExternalDocs().getUrl());
        assertEquals("https://op", get.getServers().get(0).getUrl());
        assertEquals(List.of("read"), get.getSecurity().get(0).getScheme("oauth"));
        assertEquals(Map.of("x-op", 1L), get.getExtensions());
    }

    @Test
    void parameterFieldsAndStyles() {
        List<Parameter> parameters = get().getParameters();
        Parameter limit = parameters.get(0);

        assertEquals(Parameter.In.QUERY, limit.getIn());
        assertEquals(Boolean.FALSE, limit.getRequired());
        assertEquals("max", limit.getDescription());
        assertEquals(Boolean.TRUE, limit.getDeprecated());
        assertEquals(Boolean.TRUE, limit.getAllowEmptyValue());
        assertEquals(Parameter.Style.FORM, limit.getStyle());
        assertEquals(Boolean.TRUE, limit.getExplode());
        assertEquals(Boolean.FALSE, limit.getAllowReserved());
        assertNotNull(limit.getSchema());
        assertEquals(5L, limit.getExample());
        assertEquals(5L, limit.getExamples().get("five").getValue());
        assertEquals(Map.of("x-pa", 1L), limit.getExtensions());
        assertEquals(Arrays.asList(Parameter.In.PATH, Parameter.In.COOKIE, null, null),
                parameters.subList(1, 5).stream().map(Parameter::getIn).toList());
        assertEquals(Arrays.asList(Parameter.Style.MATRIX, Parameter.Style.LABEL, Parameter.Style.SIMPLE,
                        Parameter.Style.SPACEDELIMITED, Parameter.Style.PIPEDELIMITED, Parameter.Style.DEEPOBJECT,
                        null, null),
                parameters.subList(1, 9).stream().map(Parameter::getStyle).toList());
        Parameter ref = parameters.get(9);
        assertEquals("#/components/parameters/p", ref.getRef());
        assertNotNull(ref.getContent().getMediaType("text/plain"));
    }

    @Test
    void requestBodyMediaTypeAndEncodings() {
        RequestBody body = get().getRequestBody();
        MediaType json = body.getContent().getMediaType(JSON_MEDIA);
        Encoding file = json.getEncoding().get("file");

        assertEquals("body", body.getDescription());
        assertEquals(Boolean.TRUE, body.getRequired());
        assertEquals(Map.of("x-rb", 1L), body.getExtensions());
        assertEquals(Map.of(JSON_MEDIA, json), body.getContent().getMediaTypes());
        assertEquals("#/components/schemas/Pet", json.getSchema().getRef());
        assertEquals(Map.of("a", 1L), json.getExample());
        assertEquals("es", json.getExamples().get("e").getSummary());
        assertEquals(Map.of("x-m", 1L), json.getExtensions());
        assertEquals("image/png", file.getContentType());
        assertEquals("rate", file.getHeaders().get("X-Rate").getDescription());
        assertEquals(Encoding.Style.FORM, file.getStyle());
        assertEquals(Boolean.TRUE, file.getExplode());
        assertEquals(Boolean.TRUE, file.getAllowReserved());
        assertEquals(Map.of("x-e", 1L), file.getExtensions());
        assertEquals(Arrays.asList(Encoding.Style.SPACE_DELIMITED, Encoding.Style.PIPE_DELIMITED,
                        Encoding.Style.DEEP_OBJECT, null, null),
                List.of("a", "b", "c", "d", "e").stream().map(n -> json.getEncoding().get(n).getStyle()).toList());
        assertEquals(6, json.getEncoding().size());
    }

    @Test
    void responsesAndHeaders() {
        Map<String, APIResponse> responses = get().getResponses().getAPIResponses();
        APIResponse ok = responses.get("200");
        Header header = ok.getHeaders().get("X-H");

        assertEquals(2, responses.size());
        assertEquals("#/components/responses/Err", get().getResponses().getDefaultValue().getRef());
        assertEquals(Map.of("x-rs", 1L), get().getResponses().getExtensions());
        assertEquals(Map.of("x-r", 1L), ok.getExtensions());
        assertNotNull(ok.getContent().getMediaType(JSON_MEDIA));
        assertEquals(3, ok.getHeaders().size());
        assertEquals("hd", header.getDescription());
        assertEquals(Boolean.TRUE, header.getRequired());
        assertEquals(Boolean.FALSE, header.getDeprecated());
        assertEquals(Header.Style.SIMPLE, header.getStyle());
        assertEquals(Boolean.FALSE, header.getExplode());
        assertEquals("x", header.getExample());
        assertEquals("x", header.getExamples().get("ex").getValue());
        assertNotNull(header.getSchema());
        assertNotNull(header.getContent().getMediaType("text/plain"));
        assertEquals(Map.of("x-h", 1L), header.getExtensions());
        assertNull(ok.getHeaders().get("X-Form").getStyle());
        assertEquals("#/components/headers/h", ok.getHeaders().get("X-Ref").getRef());
    }

    @Test
    void linkFields() {
        Link self = get().getResponses().getAPIResponse("200").getLinks().get("self");

        assertEquals("#/paths/~1pets/get", self.getOperationRef());
        assertEquals("listPets", self.getOperationId());
        assertEquals("$request.body", self.getRequestBody());
        assertEquals("ld", self.getDescription());
        assertEquals(Map.of("id", "$response.body#/id"), self.getParameters());
        assertEquals("https://link", self.getServer().getUrl());
        assertEquals(Map.of("x-li", 1L), self.getExtensions());
    }

    @Test
    void linkReferencesAndCallbacks() {
        Operation get = get();
        Map<String, Link> links = get.getResponses().getAPIResponse("200").getLinks();
        Callback onEvent = get.getCallbacks().get("onEvent");

        assertEquals("#/components/links/L", links.get("ref").getRef());
        assertNull(links.get("flat").getParameters());
        assertEquals(3, links.size());
        assertEquals(2, get.getCallbacks().size());
        assertEquals("cb", onEvent.getPathItem("{$request.body#/url}").getPOST().getOperationId());
        assertEquals(Map.of("x-cb", 1L), onEvent.getExtensions());
        assertEquals("#/components/callbacks/cb", get.getCallbacks().get("byRef").getRef());
    }

    @Test
    void componentsMaps() {
        Components components = document().getComponents();
        Example example = components.getExamples().get("ex");

        assertEquals(Map.of("x-comp", 1L), components.getExtensions());
        assertEquals(1, components.getSchemas().size());
        assertEquals("err", components.getResponses().get("Err").getDescription());
        assertEquals(1, components.getResponses().size());
        assertEquals(Parameter.In.QUERY, components.getParameters().get("p").getIn());
        assertEquals(1, components.getParameters().size());
        assertEquals("s", example.getSummary());
        assertEquals("d", example.getDescription());
        assertEquals(Map.of("v", 1L), example.getValue());
        assertEquals("https://ex", example.getExternalValue());
        assertEquals(Map.of("x-ex", 1L), example.getExtensions());
        assertEquals("#/components/examples/ex", components.getExamples().get("ref").getRef());
        assertEquals(2, components.getExamples().size());
        assertEquals("#/components/requestBodies/x", components.getRequestBodies().get("rb").getRef());
        assertEquals(1, components.getRequestBodies().size());
        assertEquals("component header", components.getHeaders().get("h").getDescription());
        assertEquals("x", components.getLinks().get("L").getOperationId());
        assertEquals(1, components.getLinks().size());
        assertNotNull(components.getCallbacks().get("c").getPathItem("/x"));
        assertEquals(1, components.getCallbacks().size());
        assertEquals("shared", components.getPathItems().get("shared").getGET().getOperationId());
        assertEquals(1, components.getPathItems().size());
    }

    @Test
    void securitySchemes() {
        Map<String, SecurityScheme> schemes = document().getComponents().getSecuritySchemes();
        SecurityScheme key = schemes.get("key");

        assertEquals(8, schemes.size());
        assertEquals(SecurityScheme.Type.APIKEY, key.getType());
        assertEquals("X-Key", key.getName());
        assertEquals(SecurityScheme.In.HEADER, key.getIn());
        assertEquals("api key", key.getDescription());
        assertEquals(Map.of("x-ss", 1L), key.getExtensions());
        assertEquals(SecurityScheme.Type.HTTP, schemes.get(BEARER).getType());
        assertEquals(BEARER, schemes.get(BEARER).getScheme());
        assertEquals("JWT", schemes.get(BEARER).getBearerFormat());
        assertEquals(SecurityScheme.Type.OPENIDCONNECT, schemes.get("oidc").getType());
        assertEquals("https://oidc", schemes.get("oidc").getOpenIdConnectUrl());
        assertEquals(SecurityScheme.Type.MUTUALTLS, schemes.get("mtls").getType());
        assertEquals(SecurityScheme.In.QUERY, schemes.get("mtls").getIn());
        assertNull(schemes.get("odd").getType());
        assertEquals(SecurityScheme.In.COOKIE, schemes.get("odd").getIn());
        assertNull(schemes.get("none").getType());
        assertNull(schemes.get("none").getIn());
        assertNull(schemes.get("where").getIn());
        assertEquals("#/components/securitySchemes/key", schemes.get("ref").getRef());
    }

    @Test
    void objectsOfTheWrongJsonTypeAreDropped() {
        OpenAPI openAPI = new JsonDeserializer().deserialize("""
                {"info": 1, "externalDocs": 1, "servers": 1, "security": 1, "tags": 1, "paths": 1,
                 "webhooks": 1, "components": 1}
                """);

        assertNotNull(openAPI);
        assertNull(openAPI.getInfo());
        assertNull(openAPI.getExternalDocs());
        assertNull(openAPI.getServers());
        assertNull(openAPI.getSecurity());
        assertNull(openAPI.getTags());
        assertNull(openAPI.getPaths());
        assertNull(openAPI.getWebhooks());
        assertNull(openAPI.getComponents());
    }

    @Test
    void nestedObjectsOfTheWrongJsonTypeAreDropped() {
        OpenAPI openAPI = new JsonDeserializer().deserialize("""
                {"info": {"contact": 1, "license": 1},
                 "servers": [{"variables": 1}],
                 "paths": {"/a": {"get": {"parameters": 1, "requestBody": 1, "responses": 1, "callbacks": 1,
                                          "tags": 1, "servers": 1, "security": 1, "externalDocs": 1}}},
                 "components": {"schemas": 1, "responses": 1, "parameters": 1, "examples": 1, "requestBodies": 1,
                                "headers": 1, "links": 1, "callbacks": 1, "pathItems": 1, "securitySchemes": 1}}
                """);
        Operation get = openAPI.getPaths().getPathItem("/a").getGET();
        Components components = openAPI.getComponents();

        assertNull(openAPI.getInfo().getContact());
        assertNull(openAPI.getInfo().getLicense());
        assertNull(openAPI.getServers().get(0).getVariables());
        assertNull(get.getParameters());
        assertNull(get.getRequestBody());
        assertNull(get.getResponses());
        assertNull(get.getCallbacks());
        assertNull(get.getTags());
        assertNull(get.getServers());
        assertNull(get.getSecurity());
        assertNull(get.getExternalDocs());
        assertNull(components.getSchemas());
        assertNull(components.getResponses());
        assertNull(components.getParameters());
        assertNull(components.getExamples());
        assertNull(components.getRequestBodies());
        assertNull(components.getHeaders());
        assertNull(components.getLinks());
        assertNull(components.getCallbacks());
        assertNull(components.getPathItems());
        assertNull(components.getSecuritySchemes());
    }

    @Test
    void deeperObjectsOfTheWrongJsonTypeAreDropped() {
        OpenAPI openAPI = new JsonDeserializer().deserialize("""
                {"paths": {"/a": 1, "/b": {"get": {"responses": {"200": {"content": 1, "headers": 1, "links": 1}},
                  "requestBody": {"content": {"application/json": {"examples": 1, "encoding": 1, "schema": 1}}},
                  "callbacks": {"c": 1}}}}}
                """);
        Operation get = openAPI.getPaths().getPathItem("/b").getGET();
        APIResponse ok = get.getResponses().getAPIResponse("200");
        MediaType json = get.getRequestBody().getContent().getMediaType(JSON_MEDIA);

        assertNull(openAPI.getPaths().getPathItem("/a"));
        assertNull(ok.getContent());
        assertNull(ok.getHeaders());
        assertNull(ok.getLinks());
        assertNull(json.getExamples());
        assertNull(json.getEncoding());
        assertNull(json.getSchema());
        assertTrue(get.getCallbacks().isEmpty());
    }

    @Test
    void aDocumentThatIsNotAnObjectIsRejected() {
        List<Object> notAnObject = List.of(1);
        assertThrows(IllegalArgumentException.class, () -> OpenApiModelMapper.toOpenApi(notAnObject));
    }
}
