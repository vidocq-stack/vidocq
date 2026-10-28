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
package io.vidocq.grimm.internal.invoker;

import io.vidocq.grimm.internal.config.FilterConfig;
import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.OASFilter;
import org.eclipse.microprofile.openapi.OASModelReader;
import org.eclipse.microprofile.openapi.models.Components;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.Operation;
import org.eclipse.microprofile.openapi.models.PathItem;
import org.eclipse.microprofile.openapi.models.callbacks.Callback;
import org.eclipse.microprofile.openapi.models.headers.Header;
import org.eclipse.microprofile.openapi.models.links.Link;
import org.eclipse.microprofile.openapi.models.media.Schema;
import org.eclipse.microprofile.openapi.models.parameters.Parameter;
import org.eclipse.microprofile.openapi.models.parameters.RequestBody;
import org.eclipse.microprofile.openapi.models.responses.APIResponse;
import org.eclipse.microprofile.openapi.models.security.SecurityScheme;
import org.eclipse.microprofile.openapi.models.servers.Server;
import org.eclipse.microprofile.openapi.models.tags.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FilterInvoker} walks every element type of spec §4.3 and removes each element a
 * {@code filterXxx} method returns {@code null} for (§4.3.1); {@link ModelReaderInvoker} and the
 * filter instantiation reject a missing class, a class of the wrong type and a failing one.
 */
class FilterTraversalTest {

    static final String DROP = "drop";
    static final String KEEP = "keep";
    static final String VISITS = "x-visits";
    private static final String PATH = "/items";
    private static final String JSON = "application/json";
    private static final String GONE = "gone";
    private static final String KEPT = "kept";
    static final String OPERATION = "operation";
    static final String SCHEMA = "schema";

    private final FilterInvoker invoker = new FilterInvoker();

    /** Records each visit, drops each element described as {@link #DROP}, and stores the visits in filterOpenAPI. */
    public static final class DroppingFilter implements OASFilter {

        private final List<String> visits = new ArrayList<>();

        private <T> T visit(String kind, T element, String description) {
            visits.add(kind);
            return DROP.equals(description) ? null : element;
        }

        @Override public PathItem filterPathItem(PathItem item) {
            return visit("pathItem", item, item.getDescription());
        }
        @Override public Operation filterOperation(Operation op) {
            return visit(OPERATION, op, op.getDescription());
        }
        @Override public Parameter filterParameter(Parameter p) {
            return visit("parameter", p, p.getDescription());
        }
        @Override public RequestBody filterRequestBody(RequestBody body) {
            return visit("requestBody", body, body.getDescription());
        }
        @Override public APIResponse filterAPIResponse(APIResponse response) {
            return visit("response", response, response.getDescription());
        }
        @Override public Schema filterSchema(Schema schema) {
            return visit(SCHEMA, schema, schema.getDescription());
        }
        @Override public Header filterHeader(Header header) {
            return visit("header", header, header.getDescription());
        }
        @Override public Link filterLink(Link link) {
            return visit("link", link, link.getDescription());
        }
        @Override public Callback filterCallback(Callback callback) {
            return visit("callback", callback, callback.hasExtension("x-" + DROP) ? DROP : KEEP);
        }
        @Override public SecurityScheme filterSecurityScheme(SecurityScheme scheme) {
            return visit("securityScheme", scheme, scheme.getDescription());
        }
        @Override public Tag filterTag(Tag tag) {
            return visit("tag", tag, tag.getName());
        }
        @Override public Server filterServer(Server server) {
            return visit("server", server, server.getDescription());
        }
        @Override public void filterOpenAPI(OpenAPI openAPI) {
            openAPI.addExtension(VISITS, List.copyOf(visits));
        }
    }

    /** Not an {@link OASFilter}: refused by the invoker. */
    public static final class NotAFilter {
        @Override public String toString() {
            return "not a filter";
        }
    }

    /** A filter or reader whose construction fails. */
    public static final class FailingFilter implements OASFilter, OASModelReader {
        public FailingFilter() {
            throw new IllegalStateException("cannot be built");
        }
        @Override public OpenAPI buildModel() {
            return OASFactory.createOpenAPI();
        }
    }

    private OpenAPI filter(OpenAPI model) {
        return invoker.applyFilter(model, new FilterConfig(DroppingFilter.class.getName(), null));
    }

    @SuppressWarnings("unchecked")
    private static List<String> visits(OpenAPI model) {
        return (List<String>) model.getExtensions().get(VISITS);
    }

    @Test
    void operationDetailsAreFilteredAndDroppedElementsRemoved() {
        Operation op = OASFactory.createOperation()
                .addParameter(OASFactory.createParameter().name("a").schema(OASFactory.createSchema()))
                .addParameter(OASFactory.createParameter().name("b").description(DROP))
                .requestBody(OASFactory.createRequestBody().content(OASFactory.createContent()
                        .addMediaType(JSON, OASFactory.createMediaType().schema(OASFactory.createSchema()))))
                .responses(OASFactory.createAPIResponses()
                        .addAPIResponse("200", OASFactory.createAPIResponse()
                                .content(OASFactory.createContent().addMediaType(JSON, OASFactory.createMediaType()))
                                .addHeader(KEPT, OASFactory.createHeader())
                                .addHeader(GONE, OASFactory.createHeader().description(DROP))
                                .addLink(GONE, OASFactory.createLink().description(DROP)))
                        .addAPIResponse("500", OASFactory.createAPIResponse().description(DROP)))
                .addCallback(GONE, OASFactory.createCallback().addExtension("x-" + DROP, true))
                .addCallback(KEPT, OASFactory.createCallback())
                .addServer(OASFactory.createServer().url("https://a.example.com").description(DROP));
        OpenAPI model = OASFactory.createOpenAPI()
                .paths(OASFactory.createPaths().addPathItem(PATH, OASFactory.createPathItem().GET(op)));

        filter(model);

        Operation filtered = model.getPaths().getPathItem(PATH).getGET();
        assertEquals(1, filtered.getParameters().size());
        assertEquals(Map.of(), filtered.getResponses().getAPIResponse("200").getLinks());
        assertEquals(1, filtered.getResponses().getAPIResponse("200").getHeaders().size());
        assertNull(filtered.getResponses().getAPIResponse("500"));
        assertEquals(1, filtered.getCallbacks().size());
        assertEquals(List.of(), filtered.getServers());
        assertEquals(2, visits(model).stream().filter(SCHEMA::equals).count());
        assertEquals(OPERATION, visits(model).get(visits(model).indexOf("pathItem") - 1));
    }

    @Test
    void aRequestBodyFilteredToNullIsRemoved() {
        Operation op = OASFactory.createOperation()
                .requestBody(OASFactory.createRequestBody().description(DROP));
        OpenAPI model = OASFactory.createOpenAPI()
                .paths(OASFactory.createPaths().addPathItem(PATH, OASFactory.createPathItem().POST(op)));

        filter(model);

        assertNull(model.getPaths().getPathItem(PATH).getPOST().getRequestBody());
    }

    @Test
    void everyHttpMethodIsFilteredAndDroppedOperationsCleared() {
        PathItem item = OASFactory.createPathItem()
                .addParameter(OASFactory.createParameter().name("id").description(DROP))
                .addServer(OASFactory.createServer().url("https://b.example.com"));
        for (PathItem.HttpMethod method : PathItem.HttpMethod.values()) {
            item.setOperation(method, OASFactory.createOperation()
                    .description(method == PathItem.HttpMethod.GET ? KEEP : DROP));
        }
        OpenAPI model = OASFactory.createOpenAPI().paths(OASFactory.createPaths()
                .addPathItem(PATH, item)
                .addPathItem("/gone", OASFactory.createPathItem().description(DROP)));

        filter(model);

        PathItem filtered = model.getPaths().getPathItem(PATH);
        assertEquals(List.of(PathItem.HttpMethod.GET), List.copyOf(filtered.getOperations().keySet()));
        assertEquals(List.of(), filtered.getParameters());
        assertEquals(1, filtered.getServers().size());
        assertEquals(1, model.getPaths().getPathItems().size());
        assertEquals(8, visits(model).stream().filter(OPERATION::equals).count());
    }

    @Test
    void topLevelTagsServersAndEveryComponentMapAreFiltered() {
        Components components = OASFactory.createComponents()
                .addSchema("s", OASFactory.createSchema().description(DROP))
                .addHeader("h", OASFactory.createHeader().description(DROP))
                .addParameter("p", OASFactory.createParameter().description(DROP))
                .addRequestBody("b", OASFactory.createRequestBody().description(DROP))
                .addResponse("r", OASFactory.createAPIResponse().description(DROP))
                .addLink("l", OASFactory.createLink().description(DROP))
                .addCallback("c", OASFactory.createCallback().addExtension("x-" + DROP, true))
                .addSecurityScheme("k", OASFactory.createSecurityScheme().description(DROP))
                .addSecurityScheme(KEPT, OASFactory.createSecurityScheme().description(KEEP));
        OpenAPI model = OASFactory.createOpenAPI().components(components)
                .addTag(OASFactory.createTag().name(DROP)).addTag(OASFactory.createTag().name(KEEP))
                .addServer(OASFactory.createServer().url("https://c.example.com").description(DROP));

        OpenAPI result = filter(model);

        assertSame(model, result);
        assertEquals(1, result.getTags().size());
        assertEquals(List.of(), result.getServers());
        assertEquals(Map.of(), result.getComponents().getSchemas());
        assertEquals(Map.of(), result.getComponents().getHeaders());
        assertEquals(Map.of(), result.getComponents().getParameters());
        assertEquals(Map.of(), result.getComponents().getRequestBodies());
        assertEquals(Map.of(), result.getComponents().getResponses());
        assertEquals(Map.of(), result.getComponents().getLinks());
        assertEquals(Map.of(), result.getComponents().getCallbacks());
        assertEquals(1, result.getComponents().getSecuritySchemes().size());
    }

    @Test
    void anEmptyModelOnlyReachesFilterOpenAPI() {
        OpenAPI model = filter(OASFactory.createOpenAPI().paths(OASFactory.createPaths()));

        assertEquals(List.of(), visits(model));
    }

    @Test
    void aNullModelIsReturnedAsIs() {
        assertNull(invoker.applyFilter(null, new FilterConfig(DroppingFilter.class.getName(), null)));
    }

    @Test
    void aMissingFilterClassIsRejected() {
        FilterConfig config = new FilterConfig("com.example.MissingFilter", null);
        OpenAPI model = OASFactory.createOpenAPI();

        assertThrows(IllegalArgumentException.class, () -> invoker.applyFilter(model, config));
    }

    @Test
    void aClassThatIsNotAFilterIsRejected() {
        FilterConfig config = new FilterConfig(NotAFilter.class.getName(), null);
        OpenAPI model = OASFactory.createOpenAPI();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> invoker.applyFilter(model, config));
        assertTrue(e.getMessage().contains("OASFilter"), e.getMessage());
    }

    @Test
    void aFilterThatCannotBeBuiltIsRejected() {
        FilterConfig config = new FilterConfig(FailingFilter.class.getName(), null);
        OpenAPI model = OASFactory.createOpenAPI();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> invoker.applyFilter(model, config));
        assertTrue(e.getMessage().startsWith("Unable to instantiate"), e.getMessage());
    }

    @Test
    void theModelReaderIsRejectedWhenMissingOfTheWrongTypeOrFailing() {
        ModelReaderInvoker readers = new ModelReaderInvoker();
        FilterConfig missing = new FilterConfig(null, "com.example.MissingReader");
        FilterConfig wrongType = new FilterConfig(null, NotAFilter.class.getName());
        FilterConfig failing = new FilterConfig(null, FailingFilter.class.getName());

        assertTrue(assertThrows(IllegalArgumentException.class, () -> readers.invokeModelReader(missing))
                .getMessage().contains("not found"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> readers.invokeModelReader(wrongType))
                .getMessage().contains("OASModelReader"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> readers.invokeModelReader(failing))
                .getMessage().startsWith("Unable to instantiate"));
    }
}
