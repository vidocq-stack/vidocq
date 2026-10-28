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
package io.vidocq.grimm.internal.merger;

import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.Operation;
import org.eclipse.microprofile.openapi.models.PathItem;
import org.eclipse.microprofile.openapi.models.callbacks.Callback;
import org.eclipse.microprofile.openapi.models.media.MediaType;
import org.eclipse.microprofile.openapi.models.media.Schema;
import org.eclipse.microprofile.openapi.models.parameters.Parameter;
import org.eclipse.microprofile.openapi.models.responses.APIResponse;
import org.eclipse.microprofile.openapi.models.servers.Server;
import org.eclipse.microprofile.openapi.models.tags.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Element-level merge of the three model sources (spec "Processing rules": annotations &gt;
 * static file &gt; {@code OASModelReader}): every nested element the merger walks.
 */
class ModelMergerDeepMergeTest {

    private static final String PATH = "/items";
    private static final String JSON = "application/json";
    private static final String SERVER_URL = "https://api.example.com";
    private static final String X_KEY = "x-key";
    private static final String LOW = "low";
    private static final String HIGH = "high";
    private static final String OK = "200";
    private static final String TAG = "pets";
    private static final String CALLBACK = "onEvent";
    private static final String EXPRESSION = "{$request.body#/url}"; private static final String CREATED = "created"; private static final String OAUTH = "oauth"; private static final String BASIC = "basic"; private static final String SHARED = "shared"; private static final String STORES = "stores";

    private final ModelMerger merger = new ModelMerger();

    private OpenAPI merge(OpenAPI reader, OpenAPI staticFile, OpenAPI annotations) {
        return merger.merge(List.of(new ReaderSource(reader), new StaticFileSource(staticFile),
                new AnnotationSource(annotations)));
    }

    private static OpenAPI withPath(PathItem item) {
        return OASFactory.createOpenAPI().paths(OASFactory.createPaths().addPathItem(PATH, item));
    }

    @Test
    void nullSourcesAreRejected() {
        assertThrows(NullPointerException.class, () -> merger.merge(null));
    }

    @Test
    void topLevelScalarsOfTheHigherSourceWin() {
        OpenAPI reader = OASFactory.createOpenAPI().openapi("3.1.0").jsonSchemaDialect(LOW)
                .externalDocs(OASFactory.createExternalDocumentation().url(LOW))
                .addExtension(X_KEY, LOW).addExtension("x-reader", LOW);
        OpenAPI annotations = OASFactory.createOpenAPI().openapi("3.1.1").jsonSchemaDialect(HIGH)
                .externalDocs(OASFactory.createExternalDocumentation().url(HIGH))
                .addExtension(X_KEY, HIGH);

        OpenAPI result = merge(reader, OASFactory.createOpenAPI(), annotations);

        assertEquals("3.1.1", result.getOpenapi());
        assertEquals(HIGH, result.getJsonSchemaDialect());
        assertEquals(HIGH, result.getExternalDocs().getUrl());
        assertEquals(HIGH, result.getExtensions().get(X_KEY));
        assertEquals(LOW, result.getExtensions().get("x-reader"));
    }

    @Test
    void webhooksAreUnionedAndCollidingItemsMerged() {
        OpenAPI reader = OASFactory.createOpenAPI()
                .addWebhook(CREATED, OASFactory.createPathItem().summary(LOW))
                .addWebhook("deleted", OASFactory.createPathItem().summary(LOW));
        OpenAPI annotations = OASFactory.createOpenAPI()
                .addWebhook(CREATED, OASFactory.createPathItem().summary(HIGH)
                        .POST(OASFactory.createOperation().operationId("onCreated")));

        Map<String, PathItem> webhooks = merge(reader, OASFactory.createOpenAPI(), annotations).getWebhooks();

        assertEquals(2, webhooks.size());
        assertEquals(HIGH, webhooks.get(CREATED).getSummary());
        assertEquals("onCreated", webhooks.get(CREATED).getPOST().getOperationId());
        assertEquals(LOW, webhooks.get("deleted").getSummary());
    }

    @Test
    void serversWithTheSameUrlTakeTheHigherDescriptionAndUnionVariables() {
        Server low = OASFactory.createServer().url(SERVER_URL).description(LOW)
                .addVariable("region", OASFactory.createServerVariable().defaultValue("eu"));
        Server high = OASFactory.createServer().url(SERVER_URL).description(HIGH)
                .addVariable("stage", OASFactory.createServerVariable().defaultValue("prod"));
        Server other = OASFactory.createServer().url("https://other.example.com");

        List<Server> servers = merge(OASFactory.createOpenAPI().addServer(low), OASFactory.createOpenAPI(),
                OASFactory.createOpenAPI().addServer(high).addServer(other)).getServers();

        assertEquals(2, servers.size());
        assertEquals(HIGH, servers.get(0).getDescription());
        assertEquals(2, servers.get(0).getVariables().size());
        assertEquals("https://other.example.com", servers.get(1).getUrl());
    }

    @Test
    void securityOfTheHigherSourceReplacesTheLowerOne() {
        OpenAPI reader = OASFactory.createOpenAPI()
                .addSecurityRequirement(OASFactory.createSecurityRequirement().addScheme(BASIC));
        OpenAPI annotations = OASFactory.createOpenAPI()
                .addSecurityRequirement(OASFactory.createSecurityRequirement().addScheme(OAUTH));

        OpenAPI result = merge(reader, OASFactory.createOpenAPI(), annotations);

        assertEquals(1, result.getSecurity().size());
        assertTrue(result.getSecurity().get(0).hasScheme(OAUTH));
    }

    @Test
    void collidingTagsTakeTheHigherDescriptionDocsAndExtensions() {
        Tag low = OASFactory.createTag().name(TAG).description(LOW).addExtension(X_KEY, LOW);
        Tag high = OASFactory.createTag().name(TAG).description(HIGH).addExtension(X_KEY, HIGH)
                .externalDocs(OASFactory.createExternalDocumentation().url(HIGH));

        List<Tag> tags = merge(OASFactory.createOpenAPI().addTag(low), OASFactory.createOpenAPI(),
                OASFactory.createOpenAPI().addTag(high).addTag(OASFactory.createTag().name(STORES))).getTags();

        assertEquals(2, tags.size());
        assertEquals(HIGH, tags.get(0).getDescription());
        assertEquals(HIGH, tags.get(0).getExternalDocs().getUrl());
        assertEquals(HIGH, tags.get(0).getExtensions().get(X_KEY));
    }

    @Test
    void collidingPathItemsMergeTheirFieldsParametersAndServers() {
        PathItem low = OASFactory.createPathItem().summary(LOW).description(LOW)
                .addParameter(OASFactory.createParameter().name("id").in(Parameter.In.PATH).description(LOW))
                .addServer(OASFactory.createServer().url(SERVER_URL))
                .addExtension(X_KEY, LOW);
        PathItem high = OASFactory.createPathItem().summary(HIGH).description(HIGH).ref("#/components/pathItems/p")
                .addParameter(OASFactory.createParameter().name("id").in(Parameter.In.PATH).description(HIGH))
                .addParameter(OASFactory.createParameter().name("q").in(Parameter.In.QUERY))
                .addServer(OASFactory.createServer().url("https://two.example.com"))
                .addExtension(X_KEY, HIGH);

        PathItem merged = merge(withPath(low), OASFactory.createOpenAPI(), withPath(high))
                .getPaths().getPathItem(PATH);

        assertEquals(HIGH, merged.getSummary());
        assertEquals(HIGH, merged.getDescription());
        assertEquals("#/components/pathItems/p", merged.getRef());
        assertEquals(2, merged.getParameters().size());
        assertEquals(HIGH, merged.getParameters().get(0).getDescription());
        assertEquals(2, merged.getServers().size());
        assertEquals(HIGH, merged.getExtensions().get(X_KEY));
    }

    @Test
    void everyHttpMethodIsMergedOrAdded() {
        PathItem low = OASFactory.createPathItem();
        PathItem high = OASFactory.createPathItem();
        for (PathItem.HttpMethod method : PathItem.HttpMethod.values()) {
            low.setOperation(method, OASFactory.createOperation().summary(LOW).description(method.name()));
            high.setOperation(method, OASFactory.createOperation().summary(HIGH));
        }
        PathItem addedOnly = OASFactory.createPathItem().TRACE(OASFactory.createOperation().summary(HIGH));

        PathItem merged = merge(withPath(low), OASFactory.createOpenAPI(), withPath(high))
                .getPaths().getPathItem(PATH);
        PathItem added = merge(withPath(OASFactory.createPathItem()), OASFactory.createOpenAPI(),
                withPath(addedOnly)).getPaths().getPathItem(PATH);

        for (PathItem.HttpMethod method : PathItem.HttpMethod.values()) {
            Operation op = merged.getOperations().get(method);
            assertEquals(HIGH, op.getSummary(), method.name());
            assertEquals(method.name(), op.getDescription());
        }
        assertEquals(HIGH, added.getTRACE().getSummary());
        assertNull(added.getGET());
    }

    @Test
    void collidingOperationsMergeEveryField() {
        Operation low = OASFactory.createOperation().summary(LOW).description(LOW).operationId(LOW)
                .addTag(TAG).addTag(SHARED)
                .addParameter(OASFactory.createParameter().name("limit").in(Parameter.In.QUERY))
                .addCallback(CALLBACK, OASFactory.createCallback())
                .addSecurityRequirement(OASFactory.createSecurityRequirement().addScheme(BASIC));
        Operation high = OASFactory.createOperation().summary(HIGH).description(HIGH).operationId(HIGH)
                .deprecated(true).externalDocs(OASFactory.createExternalDocumentation().url(HIGH))
                .addTag(SHARED).addTag(STORES)
                .addParameter(OASFactory.createParameter().name("offset").in(Parameter.In.QUERY))
                .addServer(OASFactory.createServer().url(SERVER_URL))
                .addCallback("onOther", OASFactory.createCallback())
                .addSecurityRequirement(OASFactory.createSecurityRequirement().addScheme(OAUTH))
                .addExtension(X_KEY, HIGH);

        Operation merged = merge(withPath(OASFactory.createPathItem().GET(low)), OASFactory.createOpenAPI(),
                withPath(OASFactory.createPathItem().GET(high))).getPaths().getPathItem(PATH).getGET();

        assertEquals(HIGH, merged.getSummary());
        assertEquals(HIGH, merged.getDescription());
        assertEquals(HIGH, merged.getOperationId());
        assertTrue(merged.getDeprecated());
        assertEquals(HIGH, merged.getExternalDocs().getUrl());
        assertEquals(List.of(TAG, SHARED, STORES), merged.getTags());
        assertEquals(2, merged.getParameters().size());
        assertEquals(1, merged.getServers().size());
        assertEquals(2, merged.getCallbacks().size());
        assertTrue(merged.getSecurity().get(0).hasScheme(OAUTH));
        assertEquals(HIGH, merged.getExtensions().get(X_KEY));
    }

    @Test
    void requestBodiesMergeTheirContentAndAttributes() {
        Operation low = OASFactory.createOperation().requestBody(OASFactory.createRequestBody().description(LOW)
                .content(OASFactory.createContent().addMediaType("text/plain", OASFactory.createMediaType())));
        Operation high = OASFactory.createOperation().requestBody(OASFactory.createRequestBody().description(HIGH)
                .required(true).addExtension(X_KEY, HIGH)
                .content(OASFactory.createContent().addMediaType(JSON, OASFactory.createMediaType())));
        Operation noContentYet = OASFactory.createOperation().requestBody(OASFactory.createRequestBody());

        var body = merge(withPath(OASFactory.createPathItem().POST(low)), OASFactory.createOpenAPI(),
                withPath(OASFactory.createPathItem().POST(high))).getPaths().getPathItem(PATH).getPOST()
                .getRequestBody();
        var filled = merge(withPath(OASFactory.createPathItem().PUT(noContentYet)), OASFactory.createOpenAPI(),
                withPath(OASFactory.createPathItem().PUT(high))).getPaths().getPathItem(PATH).getPUT()
                .getRequestBody();

        assertEquals(HIGH, body.getDescription());
        assertTrue(body.getRequired());
        assertEquals(2, body.getContent().getMediaTypes().size());
        assertEquals(HIGH, body.getExtensions().get(X_KEY));
        assertTrue(filled.getContent().hasMediaType(JSON));
    }

    @Test
    void responsesMergeDescriptionContentHeadersAndLinks() {
        APIResponse low = OASFactory.createAPIResponse().description(LOW)
                .addHeader("X-Low", OASFactory.createHeader())
                .addLink("self", OASFactory.createLink().operationId(LOW));
        APIResponse high = OASFactory.createAPIResponse().description(HIGH).ref("#/components/responses/r")
                .content(OASFactory.createContent().addMediaType(JSON, OASFactory.createMediaType()))
                .addHeader("X-High", OASFactory.createHeader())
                .addLink("self", OASFactory.createLink().operationId(HIGH))
                .addExtension(X_KEY, HIGH);
        Operation lowOp = OASFactory.createOperation().responses(OASFactory.createAPIResponses()
                .addAPIResponse(OK, low).addAPIResponse("404", OASFactory.createAPIResponse().description(LOW)));
        Operation highOp = OASFactory.createOperation().responses(OASFactory.createAPIResponses()
                .addAPIResponse(OK, high).addExtension(X_KEY, HIGH));

        var responses = merge(withPath(OASFactory.createPathItem().GET(lowOp)), OASFactory.createOpenAPI(),
                withPath(OASFactory.createPathItem().GET(highOp))).getPaths().getPathItem(PATH).getGET()
                .getResponses();
        APIResponse ok = responses.getAPIResponse(OK);

        assertEquals(2, responses.getAPIResponses().size());
        assertEquals(HIGH, responses.getExtensions().get(X_KEY));
        assertEquals(HIGH, ok.getDescription());
        assertEquals("#/components/responses/r", ok.getRef());
        assertTrue(ok.getContent().hasMediaType(JSON));
        assertEquals(2, ok.getHeaders().size());
        assertEquals(HIGH, ok.getLinks().get("self").getOperationId());
        assertEquals(HIGH, ok.getExtensions().get(X_KEY));
    }

    @Test
    void collidingMediaTypesMergeExampleExamplesAndEncoding() {
        MediaType low = OASFactory.createMediaType().example(LOW)
                .addExample("first", OASFactory.createExample().value(LOW))
                .addEncoding("file", OASFactory.createEncoding().contentType("image/png"));
        MediaType high = OASFactory.createMediaType().example(HIGH)
                .schema(OASFactory.createSchema().addType(Schema.SchemaType.STRING))
                .addExample("second", OASFactory.createExample().value(HIGH))
                .addEncoding("meta", OASFactory.createEncoding().contentType(JSON))
                .addExtension(X_KEY, HIGH);

        MediaType merged = merge(responseWith(low), OASFactory.createOpenAPI(), responseWith(high))
                .getPaths().getPathItem(PATH).getGET().getResponses().getAPIResponse(OK)
                .getContent().getMediaType(JSON);

        assertEquals(HIGH, merged.getExample());
        assertEquals(List.of(Schema.SchemaType.STRING), merged.getSchema().getType());
        assertEquals(2, merged.getExamples().size());
        assertEquals(2, merged.getEncoding().size());
        assertEquals(HIGH, merged.getExtensions().get(X_KEY));
    }

    private static OpenAPI responseWith(MediaType mediaType) {
        return withPath(OASFactory.createPathItem().GET(OASFactory.createOperation().responses(
                OASFactory.createAPIResponses().addAPIResponse(OK, OASFactory.createAPIResponse()
                        .content(OASFactory.createContent().addMediaType(JSON, mediaType))))));
    }

    @Test
    void collidingSchemasMergeItemsRecursivelyAndKeepNonEmptyKeywords() {
        Schema low = OASFactory.createSchema().addType(Schema.SchemaType.ARRAY).addRequired("id")
                .items(OASFactory.createSchema().addType(Schema.SchemaType.STRING).minLength(1));
        Schema high = OASFactory.createSchema().type(List.of()).description(HIGH)
                .items(OASFactory.createSchema().maxLength(5));

        Schema merged = merge(responseWith(OASFactory.createMediaType().schema(low)), OASFactory.createOpenAPI(),
                responseWith(OASFactory.createMediaType().schema(high))).getPaths().getPathItem(PATH).getGET()
                .getResponses().getAPIResponse(OK).getContent().getMediaType(JSON).getSchema();

        assertEquals(List.of(Schema.SchemaType.ARRAY), merged.getType());
        assertEquals(List.of("id"), merged.getRequired());
        assertEquals(HIGH, merged.getDescription());
        assertEquals(1, merged.getItems().getMinLength());
        assertEquals(5, merged.getItems().getMaxLength());
    }

    @Test
    void componentCallbacksMergeTheirPathItemsAndExtensions() {
        Callback low = OASFactory.createCallback()
                .addPathItem(EXPRESSION, OASFactory.createPathItem().summary(LOW))
                .addPathItem("{$other}", OASFactory.createPathItem().summary(LOW));
        Callback high = OASFactory.createCallback()
                .addPathItem(EXPRESSION, OASFactory.createPathItem().summary(HIGH))
                .addExtension(X_KEY, HIGH);

        Callback merged = merge(OASFactory.createOpenAPI().components(
                        OASFactory.createComponents().addCallback(CALLBACK, low)), OASFactory.createOpenAPI(),
                OASFactory.createOpenAPI().components(OASFactory.createComponents().addCallback(CALLBACK, high)))
                .getComponents().getCallbacks().get(CALLBACK);

        assertEquals(2, merged.getPathItems().size());
        assertEquals(HIGH, merged.getPathItem(EXPRESSION).getSummary());
        assertEquals(HIGH, merged.getExtensions().get(X_KEY));
    }

    @Test
    void componentMapsOfTheHigherSourceOverrideCollidingEntries() {
        OpenAPI reader = OASFactory.createOpenAPI().components(OASFactory.createComponents()
                .addResponse("r", OASFactory.createAPIResponse().description(LOW))
                .addParameter("p", OASFactory.createParameter().description(LOW))
                .addExample("e", OASFactory.createExample().summary(LOW))
                .addRequestBody("b", OASFactory.createRequestBody().description(LOW))
                .addHeader("h", OASFactory.createHeader().description(LOW))
                .addSecurityScheme("s", OASFactory.createSecurityScheme().description(LOW))
                .addLink("l", OASFactory.createLink().description(LOW))
                .addPathItem("i", OASFactory.createPathItem().description(LOW))
                .addExtension(X_KEY, LOW));
        OpenAPI annotations = OASFactory.createOpenAPI().components(OASFactory.createComponents()
                .addResponse("r", OASFactory.createAPIResponse().description(HIGH))
                .addParameter("p", OASFactory.createParameter().description(HIGH))
                .addExample("e", OASFactory.createExample().summary(HIGH))
                .addRequestBody("b", OASFactory.createRequestBody().description(HIGH))
                .addHeader("h", OASFactory.createHeader().description(HIGH))
                .addSecurityScheme("s", OASFactory.createSecurityScheme().description(HIGH))
                .addLink("l", OASFactory.createLink().description(HIGH))
                .addPathItem("i", OASFactory.createPathItem().description(HIGH))
                .addExtension(X_KEY, HIGH));

        var components = merge(reader, OASFactory.createOpenAPI(), annotations).getComponents();

        assertEquals(HIGH, components.getResponses().get("r").getDescription());
        assertEquals(HIGH, components.getParameters().get("p").getDescription());
        assertEquals(HIGH, components.getExamples().get("e").getSummary());
        assertEquals(HIGH, components.getRequestBodies().get("b").getDescription());
        assertEquals(HIGH, components.getHeaders().get("h").getDescription());
        assertEquals(HIGH, components.getSecuritySchemes().get("s").getDescription());
        assertEquals(HIGH, components.getLinks().get("l").getDescription());
        assertEquals(HIGH, components.getPathItems().get("i").getDescription());
        assertEquals(HIGH, components.getExtensions().get(X_KEY));
    }

    @Test
    void emptyHigherCollectionsLeaveTheLowerValues() {
        OpenAPI reader = withPath(OASFactory.createPathItem().GET(OASFactory.createOperation().addTag(TAG)))
                .addTag(OASFactory.createTag().name(TAG)).addServer(OASFactory.createServer().url(SERVER_URL));
        OpenAPI annotations = withPath(OASFactory.createPathItem().GET(OASFactory.createOperation()))
                .tags(List.of()).servers(List.of()).security(List.of());

        OpenAPI result = merge(reader, OASFactory.createOpenAPI(), annotations);

        assertEquals(List.of(TAG), result.getPaths().getPathItem(PATH).getGET().getTags());
        assertEquals(1, result.getTags().size());
        assertEquals(1, result.getServers().size());
        assertFalse(result.getPaths().getPathItems().isEmpty());
        assertNotNull(result.getPaths().getPathItem(PATH).getGET());
    }
}
