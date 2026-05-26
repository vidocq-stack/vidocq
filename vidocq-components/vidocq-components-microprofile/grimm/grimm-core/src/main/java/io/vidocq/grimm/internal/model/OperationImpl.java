package io.vidocq.grimm.internal.model;

import org.eclipse.microprofile.openapi.models.ExternalDocumentation;
import org.eclipse.microprofile.openapi.models.Operation;
import org.eclipse.microprofile.openapi.models.callbacks.Callback;
import org.eclipse.microprofile.openapi.models.parameters.Parameter;
import org.eclipse.microprofile.openapi.models.parameters.RequestBody;
import org.eclipse.microprofile.openapi.models.responses.APIResponses;
import org.eclipse.microprofile.openapi.models.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.models.servers.Server;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class OperationImpl extends AbstractExtensible<Operation> implements Operation {

    private List<String> tags;
    private String summary;
    private String description;
    private ExternalDocumentation externalDocs;
    private String operationId;
    private List<Parameter> parameters;
    private RequestBody requestBody;
    private APIResponses responses;
    private Map<String, Callback> callbacks;
    private Boolean deprecated;
    private List<SecurityRequirement> security;
    private List<Server> servers;

    @Override public List<String> getTags() { return ModelCollections.immutableListView(tags); }
    @Override public void setTags(List<String> tags) { this.tags = ModelCollections.mutableList(tags); }

    @Override
    public Operation addTag(String tag) {
        if (tag == null) return this;
        tags = ModelCollections.copyOnWriteList(tags);
        tags.add(tag);
        return this;
    }

    @Override
    public void removeTag(String tag) {
        if (tags != null) {
            tags = ModelCollections.copyOnWriteList(tags);
            tags.remove(tag);
        }
    }

    @Override public String getSummary() { return summary; }
    @Override public void setSummary(String summary) { this.summary = summary; }

    @Override public String getDescription() { return description; }
    @Override public void setDescription(String description) { this.description = description; }

    @Override public ExternalDocumentation getExternalDocs() { return externalDocs; }
    @Override public void setExternalDocs(ExternalDocumentation externalDocs) { this.externalDocs = externalDocs; }

    @Override public String getOperationId() { return operationId; }
    @Override public void setOperationId(String operationId) { this.operationId = operationId; }

    @Override public List<Parameter> getParameters() { return ModelCollections.immutableListView(parameters); }
    @Override public void setParameters(List<Parameter> parameters) { this.parameters = ModelCollections.mutableList(parameters); }

    @Override
    public Operation addParameter(Parameter parameter) {
        if (parameter == null) return this;
        parameters = ModelCollections.copyOnWriteList(parameters);
        parameters.add(parameter);
        return this;
    }

    @Override
    public void removeParameter(Parameter parameter) {
        if (parameters != null) {
            parameters = ModelCollections.copyOnWriteList(parameters);
            parameters.remove(parameter);
        }
    }

    @Override public RequestBody getRequestBody() { return requestBody; }
    @Override public void setRequestBody(RequestBody requestBody) { this.requestBody = requestBody; }

    @Override public APIResponses getResponses() { return responses; }
    @Override public void setResponses(APIResponses responses) { this.responses = responses; }

    @Override public Map<String, Callback> getCallbacks() { return ModelCollections.immutableMapView(callbacks); }
    @Override public void setCallbacks(Map<String, Callback> callbacks) { this.callbacks = ModelCollections.mutableMap(callbacks); }

    @Override
    public Operation addCallback(String key, Callback callback) {
        if (callback == null) return this;
        callbacks = ModelCollections.copyOnWriteMap(callbacks);
        callbacks.put(key, callback);
        return this;
    }

    @Override
    public void removeCallback(String key) {
        if (callbacks != null) {
            callbacks = ModelCollections.copyOnWriteMap(callbacks);
            callbacks.remove(key);
        }
    }

    @Override public Boolean getDeprecated() { return deprecated; }
    @Override public void setDeprecated(Boolean deprecated) { this.deprecated = deprecated; }

    @Override public List<SecurityRequirement> getSecurity() { return ModelCollections.immutableListView(security); }
    @Override public void setSecurity(List<SecurityRequirement> security) { this.security = ModelCollections.mutableList(security); }

    @Override
    public Operation addSecurityRequirement(SecurityRequirement securityRequirement) {
        if (securityRequirement == null) return this;
        security = ModelCollections.copyOnWriteList(security);
        security.add(securityRequirement);
        return this;
    }

    @Override
    public void removeSecurityRequirement(SecurityRequirement securityRequirement) {
        if (security != null) {
            security = ModelCollections.copyOnWriteList(security);
            security.remove(securityRequirement);
        }
    }

    @Override public List<Server> getServers() { return ModelCollections.immutableListView(servers); }
    @Override public void setServers(List<Server> servers) { this.servers = ModelCollections.mutableList(servers); }

    @Override
    public Operation addServer(Server server) {
        if (server == null) return this;
        servers = ModelCollections.copyOnWriteList(servers);
        servers.add(server);
        return this;
    }

    @Override
    public void removeServer(Server server) {
        if (servers != null) {
            servers = ModelCollections.copyOnWriteList(servers);
            servers.remove(server);
        }
    }
}

