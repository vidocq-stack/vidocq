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
package io.vidocq.grimm.internal.serialization;

import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.security.OAuthFlow;
import org.eclipse.microprofile.openapi.models.security.OAuthFlows;
import org.eclipse.microprofile.openapi.models.security.SecurityScheme;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * BUG-20261028-20: the OAuth flows of a security scheme declared in a static file (spec §4.2,
 * OpenAPI 3.1 §4.8.27 "OAuth Flows Object") must reach the model, and therefore the served document.
 */
class StaticOAuthFlowsTest {

    private static final String YAML = """
            openapi: 3.1.0
            info:
              title: flows
              version: "1"
            components:
              securitySchemes:
                oauth:
                  type: oauth2
                  flows:
                    implicit:
                      authorizationUrl: https://auth.example.com/implicit
                      scopes:
                        read: read access
                    password:
                      tokenUrl: https://auth.example.com/password
                      scopes: not a map
                    clientCredentials:
                      tokenUrl: https://auth.example.com/client
                      refreshUrl: https://auth.example.com/refresh
                      x-flow: client
                    authorizationCode:
                      authorizationUrl: https://auth.example.com/authorize
                      tokenUrl: https://auth.example.com/token
                      scopes:
                        write: write access
                    x-flows: all
                    ignored: true
            """;

    private static OAuthFlows flows() {
        OpenAPI openAPI = new YamlDeserializer().deserialize(YAML);
        SecurityScheme scheme = openAPI.getComponents().getSecuritySchemes().get("oauth");
        assertEquals(SecurityScheme.Type.OAUTH2, scheme.getType());
        assertNotNull(scheme.getFlows(), "flows of a static oauth2 scheme are dropped");
        return scheme.getFlows();
    }

    @Test
    void everyFlowOfAStaticSecuritySchemeIsMapped() {
        OAuthFlows flows = flows();

        assertEquals("https://auth.example.com/implicit", flows.getImplicit().getAuthorizationUrl());
        assertEquals(Map.of("read", "read access"), flows.getImplicit().getScopes());
        assertEquals("https://auth.example.com/password", flows.getPassword().getTokenUrl());
        assertNull(flows.getPassword().getScopes());
        OAuthFlow client = flows.getClientCredentials();
        assertEquals("https://auth.example.com/client", client.getTokenUrl());
        assertEquals("https://auth.example.com/refresh", client.getRefreshUrl());
        assertEquals(Map.of("x-flow", "client"), client.getExtensions());
        assertNull(client.getScopes());
        assertEquals("https://auth.example.com/token", flows.getAuthorizationCode().getTokenUrl());
        assertEquals(Map.of("write", "write access"), flows.getAuthorizationCode().getScopes());
        assertEquals(Map.of("x-flows", "all"), flows.getExtensions());
    }

    @Test
    void flowsSurviveTheJsonRoundTrip() {
        OpenAPI openAPI = new YamlDeserializer().deserialize(YAML);

        String json = new JsonSerializer().serialize(openAPI);
        OpenAPI back = new JsonDeserializer().deserialize(json);

        OAuthFlows flows = back.getComponents().getSecuritySchemes().get("oauth").getFlows();
        assertEquals("https://auth.example.com/authorize", flows.getAuthorizationCode().getAuthorizationUrl());
        assertEquals(Map.of("read", "read access"), flows.getImplicit().getScopes());
    }
}
