package io.vidocq.grimm.internal.model;

import org.eclipse.microprofile.openapi.models.security.SecurityRequirement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SecurityRequirementImpl implements SecurityRequirement {

    private Map<String, List<String>> schemes;

    @Override
    public SecurityRequirement addScheme(String securitySchemeName, String scope) {
        initSchemes();
        List<String> scopes = new ArrayList<>();
        if (scope != null) scopes.add(scope);
        schemes.put(securitySchemeName, scopes);
        return this;
    }

    @Override
    public SecurityRequirement addScheme(String securitySchemeName, List<String> scopes) {
        initSchemes();
        schemes.put(securitySchemeName, scopes == null ? new ArrayList<>() : scopes);
        return this;
    }

    @Override
    public SecurityRequirement addScheme(String securitySchemeName) {
        initSchemes();
        schemes.put(securitySchemeName, new ArrayList<>());
        return this;
    }

    @Override
    public void removeScheme(String securitySchemeName) {
        if (schemes != null) schemes.remove(securitySchemeName);
    }

    @Override public Map<String, List<String>> getSchemes() { return schemes; }
    @Override public void setSchemes(Map<String, List<String>> items) { this.schemes = items; }

    private void initSchemes() {
        if (schemes == null) schemes = new LinkedHashMap<>();
    }
}

