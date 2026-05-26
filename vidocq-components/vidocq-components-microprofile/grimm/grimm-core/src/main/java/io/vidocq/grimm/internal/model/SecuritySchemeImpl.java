package io.vidocq.grimm.internal.model;

import org.eclipse.microprofile.openapi.models.security.OAuthFlows;
import org.eclipse.microprofile.openapi.models.security.SecurityScheme;

public class SecuritySchemeImpl extends AbstractExtensibleRef<SecurityScheme> implements SecurityScheme {

    private Type type;
    private String description;
    private String name;
    private In in;
    private String scheme;
    private String bearerFormat;
    private OAuthFlows flows;
    private String openIdConnectUrl;

    @Override protected String resolveComponentPrefix() { return "#/components/securitySchemes/"; }

    @Override public Type getType() { return type; }
    @Override public void setType(Type type) { this.type = type; }

    @Override public String getDescription() { return description; }
    @Override public void setDescription(String description) { this.description = description; }

    @Override public String getName() { return name; }
    @Override public void setName(String name) { this.name = name; }

    @Override public In getIn() { return in; }
    @Override public void setIn(In in) { this.in = in; }

    @Override public String getScheme() { return scheme; }
    @Override public void setScheme(String scheme) { this.scheme = scheme; }

    @Override public String getBearerFormat() { return bearerFormat; }
    @Override public void setBearerFormat(String bearerFormat) { this.bearerFormat = bearerFormat; }

    @Override public OAuthFlows getFlows() { return flows; }
    @Override public void setFlows(OAuthFlows flows) { this.flows = flows; }

    @Override public String getOpenIdConnectUrl() { return openIdConnectUrl; }
    @Override public void setOpenIdConnectUrl(String openIdConnectUrl) { this.openIdConnectUrl = openIdConnectUrl; }
}

