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

