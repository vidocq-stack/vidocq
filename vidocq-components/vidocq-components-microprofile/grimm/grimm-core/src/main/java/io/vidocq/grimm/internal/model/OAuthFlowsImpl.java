package io.vidocq.grimm.internal.model;

import org.eclipse.microprofile.openapi.models.security.OAuthFlow;
import org.eclipse.microprofile.openapi.models.security.OAuthFlows;

public class OAuthFlowsImpl extends AbstractExtensible<OAuthFlows> implements OAuthFlows {

    private OAuthFlow implicit;
    private OAuthFlow password;
    private OAuthFlow clientCredentials;
    private OAuthFlow authorizationCode;

    @Override public OAuthFlow getImplicit() { return implicit; }
    @Override public void setImplicit(OAuthFlow implicit) { this.implicit = implicit; }

    @Override public OAuthFlow getPassword() { return password; }
    @Override public void setPassword(OAuthFlow password) { this.password = password; }

    @Override public OAuthFlow getClientCredentials() { return clientCredentials; }
    @Override public void setClientCredentials(OAuthFlow clientCredentials) { this.clientCredentials = clientCredentials; }

    @Override public OAuthFlow getAuthorizationCode() { return authorizationCode; }
    @Override public void setAuthorizationCode(OAuthFlow authorizationCode) { this.authorizationCode = authorizationCode; }
}

