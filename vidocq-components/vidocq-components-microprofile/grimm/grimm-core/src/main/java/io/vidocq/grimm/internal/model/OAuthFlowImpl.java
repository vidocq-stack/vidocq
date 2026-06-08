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
package io.vidocq.grimm.internal.model;

import org.eclipse.microprofile.openapi.models.security.OAuthFlow;

import java.util.LinkedHashMap;
import java.util.Map;

public class OAuthFlowImpl extends AbstractExtensible<OAuthFlow> implements OAuthFlow {

    private String authorizationUrl;
    private String tokenUrl;
    private String refreshUrl;
    private Map<String, String> scopes;

    @Override public String getAuthorizationUrl() { return authorizationUrl; }
    @Override public void setAuthorizationUrl(String authorizationUrl) { this.authorizationUrl = authorizationUrl; }

    @Override public String getTokenUrl() { return tokenUrl; }
    @Override public void setTokenUrl(String tokenUrl) { this.tokenUrl = tokenUrl; }

    @Override public String getRefreshUrl() { return refreshUrl; }
    @Override public void setRefreshUrl(String refreshUrl) { this.refreshUrl = refreshUrl; }

    @Override
    public OAuthFlow addScope(String scope, String description) {
        scopes = ModelCollections.copyOnWriteMap(scopes);
        scopes.put(scope, description == null ? "" : description);
        return this;
    }

    @Override
    public void removeScope(String scope) {
        if (scopes != null) {
            scopes = ModelCollections.copyOnWriteMap(scopes);
            scopes.remove(scope);
        }
    }

    @Override public Map<String, String> getScopes() { return ModelCollections.immutableMapView(scopes); }
    @Override public void setScopes(Map<String, String> scopes) { this.scopes = ModelCollections.mutableMap(scopes); }
}

