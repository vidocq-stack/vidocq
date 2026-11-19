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
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.Operation;
import org.eclipse.microprofile.openapi.models.PathItem;
import org.eclipse.microprofile.openapi.models.Paths;
import org.eclipse.microprofile.openapi.models.headers.Header;
import org.eclipse.microprofile.openapi.models.info.Info;
import org.eclipse.microprofile.openapi.models.media.Schema;
import org.eclipse.microprofile.openapi.models.parameters.Parameter;
import org.eclipse.microprofile.openapi.models.responses.APIResponse;
import org.eclipse.microprofile.openapi.models.responses.APIResponses;
import org.eclipse.microprofile.openapi.models.servers.Server;
import org.eclipse.microprofile.openapi.models.tags.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link FilterInvoker} and {@link ModelReaderInvoker}.
 *
 * Spec §4.1 / §4.3: Filter and ModelReader invocation.
 */
class InvokerTest {

    @Test
    void filterInvoker_noFilterConfigured_returnsModel() {
        // When no filter is configured, model should be returned unchanged
        FilterConfig config = FilterConfig.defaultConfig();
        OpenAPI model = OASFactory.createObject(OpenAPI.class)
            .info(createInfo("Test API", "1.0"));

        FilterInvoker invoker = new FilterInvoker();
        OpenAPI result = invoker.applyFilter(model, config);

        assertSame(model, result);
    }

    @Test
    void modelReaderInvoker_noReaderConfigured_returnsNull() {
        // When no reader is configured, should return null
        FilterConfig config = FilterConfig.defaultConfig();

        ModelReaderInvoker invoker = new ModelReaderInvoker();
        OpenAPI result = invoker.invokeModelReader(config);

        assertNull(result);
    }

    @Test
    void modelReaderInvoker_readerConfigured_invokesReader() {
        // Spec §4.1: OASModelReader is instantiated and called
        FilterConfig config = new FilterConfig(null, "io.vidocq.grimm.internal.invoker.InvokerTest$FakeModelReader");

        ModelReaderInvoker invoker = new ModelReaderInvoker();
        OpenAPI result = invoker.invokeModelReader(config);

        assertNotNull(result);
        assertEquals("From Reader", result.getInfo().getTitle());
    }

    @Test
    void filterInvoker_filterConfigured_appliesFilter() {
        // Spec §4.3: OASFilter is instantiated and applied
        FilterConfig config = new FilterConfig("io.vidocq.grimm.internal.invoker.InvokerTest$FakeFilter", null);
        OpenAPI model = OASFactory.createObject(OpenAPI.class)
            .info(createInfo("Test API", "1.0"));

        FilterInvoker invoker = new FilterInvoker();
        OpenAPI result = invoker.applyFilter(model, config);

        assertNotNull(result);
        // Filter modifies the title
        assertEquals("Filtered: Test API", result.getInfo().getTitle());
    }

    private Info createInfo(String title, String version) {
        return OASFactory.createObject(Info.class)
            .title(title)
            .version(version);
    }

    // Fake implementations for testing

    public static class FakeModelReader implements OASModelReader {
        @Override
        public OpenAPI buildModel() {
            return OASFactory.createObject(OpenAPI.class)
                .info(OASFactory.createObject(Info.class)
                    .title("From Reader")
                    .version("2.0"));
        }
    }

    public static class FakeFilter implements OASFilter {
        @Override
        public void filterOpenAPI(OpenAPI openAPI) {
            if (openAPI.getInfo() != null) {
                openAPI.getInfo().setTitle("Filtered: " + openAPI.getInfo().getTitle());
            }
        }
    }

    // ----------------- §4.3.1 null-return removal -----------------

    @Test
    void filterInvoker_filterPathItemReturningNullRemovesPath() {
        // Spec §4.3.1
        OpenAPI model = OASFactory.createObject(OpenAPI.class);
        Paths paths = OASFactory.createObject(Paths.class);
        paths.addPathItem("/keep", OASFactory.createObject(PathItem.class).description("keep"));
        paths.addPathItem("/drop", OASFactory.createObject(PathItem.class).description("drop"));
        model.setPaths(paths);

        new FilterInvoker().applyFilter(model,
                new FilterConfig("io.vidocq.grimm.internal.invoker.InvokerTest$DropDropPathFilter", null));

        assertTrue(model.getPaths().hasPathItem("/keep"));
        assertFalse(model.getPaths().hasPathItem("/drop"));
    }

    @Test
    void filterInvoker_filterOperationReturningNullClearsVerb() {
        // Spec §4.3.1
        OpenAPI model = OASFactory.createObject(OpenAPI.class);
        Paths paths = OASFactory.createObject(Paths.class);
        PathItem item = OASFactory.createObject(PathItem.class);
        item.setGET(OASFactory.createObject(Operation.class).operationId("getIt"));
        item.setPOST(OASFactory.createObject(Operation.class).operationId("postIt"));
        paths.addPathItem("/r", item);
        model.setPaths(paths);

        new FilterInvoker().applyFilter(model,
                new FilterConfig("io.vidocq.grimm.internal.invoker.InvokerTest$DropPostFilter", null));

        PathItem result = model.getPaths().getPathItem("/r");
        assertNotNull(result.getGET());
        assertNull(result.getPOST(), "POST must be cleared when filterOperation returns null");
    }

    @Test
    void filterInvoker_filterTagReturningNullRemovesTag() {
        // Spec §4.3.1
        OpenAPI model = OASFactory.createObject(OpenAPI.class);
        model.setTags(new ArrayList<>(List.of(
                OASFactory.createObject(Tag.class).name("keep"),
                OASFactory.createObject(Tag.class).name("drop"))));

        new FilterInvoker().applyFilter(model,
                new FilterConfig("io.vidocq.grimm.internal.invoker.InvokerTest$DropDropTagFilter", null));

        assertEquals(1, model.getTags().size());
        assertEquals("keep", model.getTags().get(0).getName());
    }

    @Test
    void filterInvoker_filterServerReturningNullRemovesServer() {
        // Spec §4.3.1
        OpenAPI model = OASFactory.createObject(OpenAPI.class);
        model.setServers(new ArrayList<>(List.of(
                OASFactory.createObject(Server.class).url("https://keep"),
                OASFactory.createObject(Server.class).url("https://drop"))));

        new FilterInvoker().applyFilter(model,
                new FilterConfig("io.vidocq.grimm.internal.invoker.InvokerTest$DropDropServerFilter", null));

        assertEquals(1, model.getServers().size());
        assertEquals("https://keep", model.getServers().get(0).getUrl());
    }

    @Test
    void filterInvoker_filterParameterReturningNullRemovesParameter() {
        // Spec §4.3.1
        OpenAPI model = OASFactory.createObject(OpenAPI.class);
        Paths paths = OASFactory.createObject(Paths.class);
        PathItem item = OASFactory.createObject(PathItem.class);
        Operation get = OASFactory.createObject(Operation.class);
        get.addParameter(OASFactory.createObject(Parameter.class).name("keep"));
        get.addParameter(OASFactory.createObject(Parameter.class).name("drop"));
        item.setGET(get);
        paths.addPathItem("/r", item);
        model.setPaths(paths);

        new FilterInvoker().applyFilter(model,
                new FilterConfig("io.vidocq.grimm.internal.invoker.InvokerTest$DropDropParamFilter", null));

        var params = model.getPaths().getPathItem("/r").getGET().getParameters();
        assertEquals(1, params.size());
        assertEquals("keep", params.get(0).getName());
    }

    @Test
    void filterInvoker_filterAPIResponseReturningNullRemovesResponse() {
        // Spec §4.3.1
        OpenAPI model = OASFactory.createObject(OpenAPI.class);
        Paths paths = OASFactory.createObject(Paths.class);
        PathItem item = OASFactory.createObject(PathItem.class);
        Operation get = OASFactory.createObject(Operation.class);
        APIResponses responses = OASFactory.createObject(APIResponses.class);
        responses.addAPIResponse("200", OASFactory.createObject(APIResponse.class).description("ok"));
        responses.addAPIResponse("500", OASFactory.createObject(APIResponse.class).description("drop"));
        get.setResponses(responses);
        item.setGET(get);
        paths.addPathItem("/r", item);
        model.setPaths(paths);

        new FilterInvoker().applyFilter(model,
                new FilterConfig("io.vidocq.grimm.internal.invoker.InvokerTest$Drop500Filter", null));

        var got = model.getPaths().getPathItem("/r").getGET().getResponses();
        assertNotNull(got.getAPIResponse("200"));
        assertNull(got.getAPIResponse("500"));
    }

    @Test
    void filterInvoker_filterOpenAPIIsCalledLast() {
        // Spec §4.3: filterOpenAPI runs after all leaf-level filters.
        // OrderTrackingFilter records the order; on filterOpenAPI it asserts.
        OpenAPI model = OASFactory.createObject(OpenAPI.class);
        Paths paths = OASFactory.createObject(Paths.class);
        paths.addPathItem("/r", OASFactory.createObject(PathItem.class)
                .GET(OASFactory.createObject(Operation.class).operationId("x")));
        model.setPaths(paths);
        model.setTags(new ArrayList<>(List.of(OASFactory.createObject(Tag.class).name("t"))));

        OrderTrackingFilter.events.clear();
        new FilterInvoker().applyFilter(model,
                new FilterConfig("io.vidocq.grimm.internal.invoker.InvokerTest$OrderTrackingFilter", null));

        // last event must be filterOpenAPI
        var events = OrderTrackingFilter.events;
        assertFalse(events.isEmpty());
        assertEquals("filterOpenAPI", events.get(events.size() - 1));
        // leaf calls must precede filterOpenAPI
        assertTrue(events.indexOf("filterPathItem") < events.indexOf("filterOpenAPI"));
        assertTrue(events.indexOf("filterOperation") < events.indexOf("filterOpenAPI"));
        assertTrue(events.indexOf("filterTag") < events.indexOf("filterOpenAPI"));
    }

    // ----------------- Test doubles -----------------

    public static class DropDropPathFilter implements OASFilter {
        @Override public PathItem filterPathItem(PathItem p) {
            return "drop".equals(p.getDescription()) ? null : p;
        }
    }

    public static class DropPostFilter implements OASFilter {
        @Override public Operation filterOperation(Operation op) {
            return "postIt".equals(op.getOperationId()) ? null : op;
        }
    }

    public static class DropDropTagFilter implements OASFilter {
        @Override public Tag filterTag(Tag t) {
            return "drop".equals(t.getName()) ? null : t;
        }
    }

    public static class DropDropServerFilter implements OASFilter {
        @Override public Server filterServer(Server s) {
            return s.getUrl() != null && s.getUrl().endsWith("drop") ? null : s;
        }
    }

    public static class DropDropParamFilter implements OASFilter {
        @Override public Parameter filterParameter(Parameter p) {
            return "drop".equals(p.getName()) ? null : p;
        }
    }

    public static class Drop500Filter implements OASFilter {
        @Override public APIResponse filterAPIResponse(APIResponse r) {
            return "drop".equals(r.getDescription()) ? null : r;
        }
    }

    public static class OrderTrackingFilter implements OASFilter {
        static final List<String> events = new ArrayList<>();
        @Override public PathItem filterPathItem(PathItem p) { events.add("filterPathItem"); return p; }
        @Override public Operation filterOperation(Operation o) { events.add("filterOperation"); return o; }
        @Override public Parameter filterParameter(Parameter p) { events.add("filterParameter"); return p; }
        @Override public Schema filterSchema(Schema s) { events.add("filterSchema"); return s; }
        @Override public Header filterHeader(Header h) { events.add("filterHeader"); return h; }
        @Override public Tag filterTag(Tag t) { events.add("filterTag"); return t; }
        @Override public Server filterServer(Server s) { events.add("filterServer"); return s; }
        @Override public void filterOpenAPI(OpenAPI o) { events.add("filterOpenAPI"); }
    }
}



