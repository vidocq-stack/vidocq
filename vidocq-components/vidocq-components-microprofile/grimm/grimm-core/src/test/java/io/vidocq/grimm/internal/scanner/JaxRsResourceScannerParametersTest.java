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
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.UriInfo;
import org.eclipse.microprofile.openapi.annotations.enums.ParameterIn;
import org.eclipse.microprofile.openapi.annotations.enums.ParameterStyle;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameters;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.parameters.Parameter.In;
import org.eclipse.microprofile.openapi.models.parameters.Parameter.Style;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Parameter mapping of {@link JaxRsResourceScanner} (MP OpenAPI 4.2 §5.2 {@code @Parameter} /
 * {@code @Parameters}, JAX-RS parameter inference §5.1).
 */
class JaxRsResourceScannerParametersTest {

    private static final String DONE = "done";
    private static final String TRACE = "trace";
    private static final String SESSION = "session";
    private static final String RENAMED = "renamed";
    private static final String X_REQUIRED = "X-Required";
    private static final String SECRET = "secret";
    private static final String TEXT = "text/plain";
    private static final String PARAMS = "/params";

    private static OpenAPI scan(Class<?> resource) {
        return new AnnotationScanner(ScanConfig.defaultConfig()).scanClasses(List.of(resource));
    }

    private static Map<String, org.eclipse.microprofile.openapi.models.parameters.Parameter> parametersOf(
            OpenAPI model, String path) {
        Map<String, org.eclipse.microprofile.openapi.models.parameters.Parameter> byName = new HashMap<>();
        for (var parameter : model.getPaths().getPathItem(path).getGET().getParameters()) {
            byName.put(parameter.getName(), parameter);
        }
        return byName;
    }

    @Path(PARAMS)
    static class ParametersResource {
        @GET
        @Path("{id}")
        @Parameters({
                @Parameter(name = TRACE, in = ParameterIn.HEADER, description = "Trace id", required = true,
                        deprecated = true, allowEmptyValue = true, example = "abc"),
                @Parameter(description = "no name: skipped")
        })
        @Parameter(name = "page", in = ParameterIn.QUERY, style = ParameterStyle.FORM)
        public String get(@PathParam("id") String id,
                          @CookieParam(SESSION) String session,
                          @Context UriInfo uriInfo,
                          @QueryParam("tags") @Parameter(style = ParameterStyle.PIPEDELIMITED) List<String> tags,
                          @QueryParam("mode") @Parameter(name = RENAMED, in = ParameterIn.COOKIE) String mode,
                          @QueryParam("page") String duplicatePage,
                          @QueryParam("hidden") @Parameter(hidden = true) String hidden,
                          @HeaderParam(X_REQUIRED) @NotNull String required) {
            return DONE;
        }
    }

    @Test
    void explicitAndInferredParametersAreMerged() {
        var params = parametersOf(scan(ParametersResource.class), PARAMS + "/{id}");

        assertEquals(Set.of(TRACE, "page", "id", SESSION, "tags", RENAMED, X_REQUIRED),
                params.keySet());

        var trace = params.get(TRACE);
        assertEquals(In.HEADER, trace.getIn());
        assertEquals("Trace id", trace.getDescription());
        assertEquals(Boolean.TRUE, trace.getRequired());
        assertEquals(Boolean.TRUE, trace.getDeprecated());
        assertEquals(Boolean.TRUE, trace.getAllowEmptyValue());
        assertEquals("abc", trace.getExample());

        assertEquals(Style.FORM, params.get("page").getStyle());
        assertEquals(In.PATH, params.get("id").getIn());
        assertEquals(Boolean.TRUE, params.get("id").getRequired());
        assertEquals(In.COOKIE, params.get(SESSION).getIn());
        assertEquals(Style.PIPEDELIMITED, params.get("tags").getStyle());
        assertEquals(In.COOKIE, params.get(RENAMED).getIn());
        // Bean Validation §5.3: @NotNull makes the parameter required.
        assertEquals(Boolean.TRUE, params.get(X_REQUIRED).getRequired());
        assertNotNull(params.get("tags").getSchema());
    }

    @Path("/styles")
    static class StylesResource {
        @GET
        @Parameter(name = "m", in = ParameterIn.PATH, style = ParameterStyle.MATRIX)
        @Parameter(name = "l", in = ParameterIn.PATH, style = ParameterStyle.LABEL)
        @Parameter(name = "s", in = ParameterIn.HEADER, style = ParameterStyle.SIMPLE)
        @Parameter(name = "sp", in = ParameterIn.QUERY, style = ParameterStyle.SPACEDELIMITED)
        @Parameter(name = "d", in = ParameterIn.QUERY, style = ParameterStyle.DEEPOBJECT)
        @Parameter(name = "noIn")
        public String get() {
            return DONE;
        }
    }

    @Test
    void everyParameterStyleIsMapped() {
        var params = parametersOf(scan(StylesResource.class), "/styles");

        assertEquals(Style.MATRIX, params.get("m").getStyle());
        assertEquals(Style.LABEL, params.get("l").getStyle());
        assertEquals(Style.SIMPLE, params.get("s").getStyle());
        assertEquals(Style.SPACEDELIMITED, params.get("sp").getStyle());
        assertEquals(Style.DEEPOBJECT, params.get("d").getStyle());
        assertEquals(Boolean.TRUE, params.get("m").getRequired());
        assertNull(params.get("noIn").getIn());
        assertNull(params.get("noIn").getStyle());
    }

    @Path("/content")
    static class ContentResource {
        @GET
        public String get(
                @QueryParam("filter")
                @Parameter(content = {@Content(mediaType = TEXT, example = "a=b"), @Content}) String filter,
                @QueryParam("limit") @Parameter(schema = @Schema(type = SchemaType.INTEGER, minimum = "1")) String limit,
                @QueryParam("site") @Schema(description = "From the site") String site,
                @QueryParam(SECRET) @Schema(hidden = true) String secret) {
            return DONE;
        }
    }

    @Test
    void contentSchemaAndHiddenSchemaOfParameters() {
        // §5.2 @Parameter: content and schema are mutually exclusive; content wins.
        var params = parametersOf(scan(ContentResource.class), "/content");

        var filter = params.get("filter");
        assertNull(filter.getSchema());
        assertEquals("a=b", filter.getContent().getMediaType(TEXT).getExample());
        assertNotNull(filter.getContent().getMediaType("*/*"));
        assertNotNull(filter.getContent().getMediaType(TEXT).getSchema());

        var limit = params.get("limit").getSchema();
        assertEquals(List.of(org.eclipse.microprofile.openapi.models.media.Schema.SchemaType.INTEGER), limit.getType());
        assertEquals(java.math.BigDecimal.ONE, limit.getMinimum());

        assertEquals("From the site", params.get("site").getSchema().getDescription());
        // A hidden site @Schema keeps the parameter but publishes no schema.
        assertNull(params.get(SECRET).getSchema());
        assertFalse(params.get(SECRET).getName().isEmpty());
    }
}
