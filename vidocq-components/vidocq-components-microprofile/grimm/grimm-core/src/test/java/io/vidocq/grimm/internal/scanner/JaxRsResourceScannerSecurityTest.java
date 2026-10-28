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
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.annotations.callbacks.Callback;
import org.eclipse.microprofile.openapi.annotations.callbacks.CallbackOperation;
import org.eclipse.microprofile.openapi.annotations.callbacks.Callbacks;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeIn;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.security.OAuthFlow;
import org.eclipse.microprofile.openapi.annotations.security.OAuthFlows;
import org.eclipse.microprofile.openapi.annotations.security.OAuthScope;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirementsSet;
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme;
import org.eclipse.microprofile.openapi.annotations.security.SecuritySchemes;
import org.eclipse.microprofile.openapi.annotations.servers.Server;
import org.eclipse.microprofile.openapi.annotations.servers.ServerVariable;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.security.SecurityScheme.In;
import org.eclipse.microprofile.openapi.models.security.SecurityScheme.Type;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Security schemes and requirements, operation servers and callbacks of
 * {@link JaxRsResourceScanner} (MP OpenAPI 4.2 §5.2 {@code @SecurityScheme},
 * {@code @SecurityRequirement}, {@code @SecurityRequirementsSet}, {@code @Server},
 * {@code @Callback}).
 */
class JaxRsResourceScannerSecurityTest {

    private static final String DONE = "done";
    private static final String COOKIE_KEY = "cookieKey";
    private static final String BEARER = "bearer";
    private static final String SHARED = "shared";
    private static final String NO_FLOWS = "noFlows";
    private static final String HOOKS_PATH = "/hooks";
    private static final String ON_EVENT = "onEvent";
    private static final String SINGLE = "single";
    private static final String OAUTH = "oauth";
    private static final String READ = "read";
    private static final String API_KEY = "apiKey";
    private static final String TOKEN_URL = "https://auth.example/token";

    private static OpenAPI scan(Class<?> resource) {
        return new AnnotationScanner(ScanConfig.defaultConfig()).scanClasses(List.of(resource));
    }

    /** An empty requirement ({@code {}}) means "no security" (OpenAPI 3.1 §4.8.30). */
    private static boolean isEmpty(org.eclipse.microprofile.openapi.models.security.SecurityRequirement requirement) {
        return requirement.getSchemes() == null || requirement.getSchemes().isEmpty();
    }

    @SecuritySchemes({
            @SecurityScheme(securitySchemeName = API_KEY, type = SecuritySchemeType.APIKEY, apiKeyName = "X-Key",
                    in = SecuritySchemeIn.QUERY, description = "Key in the query"),
            @SecurityScheme(securitySchemeName = COOKIE_KEY, type = SecuritySchemeType.APIKEY, apiKeyName = "k",
                    in = SecuritySchemeIn.COOKIE),
            @SecurityScheme(securitySchemeName = BEARER, type = SecuritySchemeType.HTTP, scheme = BEARER,
                    bearerFormat = "JWT"),
            @SecurityScheme(securitySchemeName = "oidc", type = SecuritySchemeType.OPENIDCONNECT,
                    openIdConnectUrl = "https://auth.example/.well-known/openid-configuration"),
            @SecurityScheme(securitySchemeName = "tls", type = SecuritySchemeType.MUTUALTLS),
            @SecurityScheme(securitySchemeName = SHARED, ref = "#/components/securitySchemes/Shared"),
            @SecurityScheme(securitySchemeName = OAUTH, type = SecuritySchemeType.OAUTH2, flows = @OAuthFlows(
                    password = @OAuthFlow(tokenUrl = TOKEN_URL, refreshUrl = "https://auth.example/refresh"),
                    clientCredentials = @OAuthFlow(tokenUrl = TOKEN_URL,
                            scopes = {@OAuthScope(name = READ, description = "Read"), @OAuthScope(description = "nameless")}),
                    authorizationCode = @OAuthFlow(authorizationUrl = "https://auth.example/authorize",
                            tokenUrl = TOKEN_URL))),
            @SecurityScheme(securitySchemeName = NO_FLOWS, type = SecuritySchemeType.OAUTH2),
            @SecurityScheme(description = "nameless")
    })
    static class SchemesOnly {
        @Override
        public String toString() {
            return "carries the security schemes";
        }
    }

    @Test
    void securitySchemesOfEveryTypeAreRegisteredAsComponents() {
        // §5.2 @SecurityScheme: declared on any class, published under components/securitySchemes.
        Map<String, org.eclipse.microprofile.openapi.models.security.SecurityScheme> schemes =
                scan(SchemesOnly.class).getComponents().getSecuritySchemes();

        assertEquals(Set.of(API_KEY, COOKIE_KEY, BEARER, "oidc", "tls", SHARED, OAUTH, NO_FLOWS),
                schemes.keySet());
        assertEquals(In.QUERY, schemes.get(API_KEY).getIn());
        assertEquals("Key in the query", schemes.get(API_KEY).getDescription());
        assertEquals(In.COOKIE, schemes.get(COOKIE_KEY).getIn());
        assertEquals(Type.HTTP, schemes.get(BEARER).getType());
        assertEquals("JWT", schemes.get(BEARER).getBearerFormat());
        assertEquals(Type.OPENIDCONNECT, schemes.get("oidc").getType());
        assertTrue(schemes.get("oidc").getOpenIdConnectUrl().endsWith("openid-configuration"));
        assertEquals(Type.MUTUALTLS, schemes.get("tls").getType());
        assertEquals("#/components/securitySchemes/Shared", schemes.get(SHARED).getRef());
        assertNull(schemes.get(NO_FLOWS).getFlows());

        var flows = schemes.get(OAUTH).getFlows();
        assertNull(flows.getImplicit());
        assertEquals("https://auth.example/refresh", flows.getPassword().getRefreshUrl());
        assertEquals(Map.of(READ, "Read"), flows.getClientCredentials().getScopes());
        assertEquals(TOKEN_URL, flows.getAuthorizationCode().getTokenUrl());
    }

    @Path("/secured")
    @SecurityRequirement(name = API_KEY)
    @SecurityRequirementsSet({@SecurityRequirement(name = OAUTH, scopes = READ), @SecurityRequirement(name = BEARER)})
    @Server(url = "https://class.example", description = "Class server",
            variables = {@ServerVariable(name = "region", defaultValue = "eu", enumeration = {"eu", "us"},
                    description = "Region"), @ServerVariable(name = "", defaultValue = "ignored")})
    @Server(url = "")
    static class SecuredResource {
        @GET
        public String inherited() {
            return DONE;
        }

        @GET
        @Path("override")
        @SecurityRequirementsSet({})
        @SecurityRequirement(name = "")
        @Server(url = "https://method.example")
        public String override() {
            return DONE;
        }

        @GET
        @Path("sets")
        @SecurityRequirementsSet(@SecurityRequirement(name = ""))
        @SecurityRequirementsSet(@SecurityRequirement(name = "tls"))
        public String sets() {
            return DONE;
        }
    }

    @Test
    void classRequirementsAndServersApplyToOperationsWithoutTheirOwn() {
        var op = scan(SecuredResource.class).getPaths().getPathItem("/secured").getGET();

        assertEquals(2, op.getSecurity().size());
        assertEquals(List.of(), op.getSecurity().get(0).getScheme(API_KEY));
        assertEquals(List.of(READ), op.getSecurity().get(1).getScheme(OAUTH));
        assertNotNull(op.getSecurity().get(1).getScheme(BEARER));

        assertEquals(1, op.getServers().size());
        var server = op.getServers().get(0);
        assertEquals("Class server", server.getDescription());
        var region = server.getVariables().get("region");
        assertEquals("eu", region.getDefaultValue());
        assertEquals(List.of("eu", "us"), region.getEnumeration());
        assertEquals("Region", region.getDescription());
        assertEquals(1, server.getVariables().size());
    }

    @Test
    void methodRequirementSetsReplaceTheClassOnes() {
        // §5.2 @SecurityRequirementsSet: an empty set means "no security" for this operation.
        OpenAPI model = scan(SecuredResource.class);
        var override = model.getPaths().getPathItem("/secured/override").getGET();

        assertEquals(1, override.getSecurity().size());
        assertTrue(isEmpty(override.getSecurity().get(0)));
        assertEquals("https://method.example", override.getServers().get(0).getUrl());

        var sets = model.getPaths().getPathItem("/secured/sets").getGET();
        assertEquals(1, sets.getSecurity().size());
        assertNotNull(sets.getSecurity().get(0).getScheme("tls"));
    }

    @Path(HOOKS_PATH)
    static class CallbacksResource {
        @POST
        @Callbacks({
                @Callback(name = ON_EVENT, callbackUrlExpression = "{$request.body#/url}",
                        extensions = @org.eclipse.microprofile.openapi.annotations.extensions.Extension(
                                name = "x-cb", value = "1"),
                        operations = {
                                @CallbackOperation(method = "post", summary = "Event", description = "Delivers an event",
                                        security = {@SecurityRequirement(name = API_KEY), @SecurityRequirement(name = "")},
                                        securitySets = {@SecurityRequirementsSet({}),
                                                @SecurityRequirementsSet(@SecurityRequirement(name = OAUTH, scopes = READ)),
                                                @SecurityRequirementsSet(@SecurityRequirement(name = ""))},
                                        responses = {
                                                @APIResponse(responseCode = "204", description = "Received"),
                                                @APIResponse(description = "Failure", ref = "#/components/responses/Error",
                                                        content = {@Content(mediaType = "application/json"), @Content})
                                        }),
                                @CallbackOperation(method = "put")
                        }),
                @Callback(name = SHARED, ref = "#/components/callbacks/Shared"),
                @Callback(callbackUrlExpression = "{$url}")
        })
        @Callback(name = SINGLE, callbackUrlExpression = "{$request.query.cb}")
        public String subscribe() {
            return DONE;
        }
    }

    @Test
    void callbacksWithOperationsSecurityAndResponsesAreMapped() {
        // §5.2 @Callback / @CallbackOperation.
        var callbacks = scan(CallbacksResource.class).getPaths().getPathItem(HOOKS_PATH).getPOST().getCallbacks();

        assertEquals(Set.of(ON_EVENT, SHARED, SINGLE), callbacks.keySet());
        assertEquals("#/components/callbacks/Shared", callbacks.get(SHARED).getRef());
        assertTrue(callbacks.get(SINGLE).getPathItems() == null || callbacks.get(SINGLE).getPathItems().isEmpty());

        var onEvent = callbacks.get(ON_EVENT);
        assertEquals("1", onEvent.getExtensions().get("x-cb"));
        var item = onEvent.getPathItem("{$request.body#/url}");
        assertNotNull(item.getPUT());
        var post = item.getPOST();
        assertEquals("Event", post.getSummary());
        assertEquals("Delivers an event", post.getDescription());
        assertEquals(3, post.getSecurity().size());
        assertNotNull(post.getSecurity().get(0).getScheme(API_KEY));
        assertTrue(isEmpty(post.getSecurity().get(1)));
        assertEquals(List.of(READ), post.getSecurity().get(2).getScheme(OAUTH));

        assertEquals("Received", post.getResponses().getAPIResponse("204").getDescription());
        var failure = post.getResponses().getDefaultValue();
        assertEquals("#/components/responses/Error", failure.getRef());
        assertEquals(Set.of("application/json", "*/*"), failure.getContent().getMediaTypes().keySet());
    }

    @Test
    void callbacksAreMappedWithoutSchemaGenerator() {
        OpenAPI model = OASFactory.createObject(OpenAPI.class);

        new JaxRsResourceScanner().scan(CallbacksResource.class, model);

        assertNotNull(model.getPaths().getPathItem(HOOKS_PATH).getPOST().getCallbacks().get(ON_EVENT));
    }
}
