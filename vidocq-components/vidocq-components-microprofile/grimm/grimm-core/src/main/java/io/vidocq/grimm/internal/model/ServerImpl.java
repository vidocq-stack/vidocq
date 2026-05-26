package io.vidocq.grimm.internal.model;

import org.eclipse.microprofile.openapi.models.servers.Server;
import org.eclipse.microprofile.openapi.models.servers.ServerVariable;

import java.util.LinkedHashMap;
import java.util.Map;

public class ServerImpl extends AbstractExtensible<Server> implements Server {

    private String url;
    private String description;
    private Map<String, ServerVariable> variables;

    @Override public String getUrl() { return url; }
    @Override public void setUrl(String url) { this.url = url; }

    @Override public String getDescription() { return description; }
    @Override public void setDescription(String description) { this.description = description; }

    @Override public Map<String, ServerVariable> getVariables() { return ModelCollections.immutableMapView(variables); }
    @Override public void setVariables(Map<String, ServerVariable> variables) { this.variables = ModelCollections.mutableMap(variables); }

    @Override
    public Server addVariable(String variableName, ServerVariable variable) {
        if (variable == null) return this;
        variables = ModelCollections.copyOnWriteMap(variables);
        variables.put(variableName, variable);
        return this;
    }

    @Override
    public void removeVariable(String variableName) {
        if (variables != null) {
            variables = ModelCollections.copyOnWriteMap(variables);
            variables.remove(variableName);
        }
    }
}

