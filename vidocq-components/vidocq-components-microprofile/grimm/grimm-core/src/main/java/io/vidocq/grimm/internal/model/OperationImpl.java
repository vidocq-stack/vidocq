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

    @Override public List<String> getTags() { return tags; }
    @Override public void setTags(List<String> tags) { this.tags = tags; }

    @Override
    public Operation addTag(String tag) {
        if (tag == null) return this;
        if (tags == null) tags = new ArrayList<>();
        tags.add(tag);
        return this;
    }

    @Override
    public void removeTag(String tag) {
        if (tags != null) tags.remove(tag);
    }

    @Override public String getSummary() { return summary; }
    @Override public void setSummary(String summary) { this.summary = summary; }

    @Override public String getDescription() { return description; }
    @Override public void setDescription(String description) { this.description = description; }

    @Override public ExternalDocumentation getExternalDocs() { return externalDocs; }
    @Override public void setExternalDocs(ExternalDocumentation externalDocs) { this.externalDocs = externalDocs; }

    @Override public String getOperationId() { return operationId; }
    @Override public void setOperationId(String operationId) { this.operationId = operationId; }

    @Override public List<Parameter> getParameters() { return parameters; }
    @Override public void setParameters(List<Parameter> parameters) { this.parameters = parameters; }

    @Override
    public Operation addParameter(Parameter parameter) {
        if (parameter == null) return this;
        if (parameters == null) parameters = new ArrayList<>();
        parameters.add(parameter);
        return this;
    }

    @Override
    public void removeParameter(Parameter parameter) {
        if (parameters != null) parameters.remove(parameter);
    }

    @Override public RequestBody getRequestBody() { return requestBody; }
    @Override public void setRequestBody(RequestBody requestBody) { this.requestBody = requestBody; }

    @Override public APIResponses getResponses() { return responses; }
    @Override public void setResponses(APIResponses responses) { this.responses = responses; }

    @Override public Map<String, Callback> getCallbacks() { return callbacks; }
    @Override public void setCallbacks(Map<String, Callback> callbacks) { this.callbacks = callbacks; }

    @Override
    public Operation addCallback(String key, Callback callback) {
        if (callback == null) return this;
        if (callbacks == null) callbacks = new LinkedHashMap<>();
        callbacks.put(key, callback);
        return this;
    }

    @Override
    public void removeCallback(String key) {
        if (callbacks != null) callbacks.remove(key);
    }

    @Override public Boolean getDeprecated() { return deprecated; }
    @Override public void setDeprecated(Boolean deprecated) { this.deprecated = deprecated; }

    @Override public List<SecurityRequirement> getSecurity() { return security; }
    @Override public void setSecurity(List<SecurityRequirement> security) { this.security = security; }

    @Override
    public Operation addSecurityRequirement(SecurityRequirement securityRequirement) {
        if (securityRequirement == null) return this;
        if (security == null) security = new ArrayList<>();
        security.add(securityRequirement);
        return this;
    }

    @Override
    public void removeSecurityRequirement(SecurityRequirement securityRequirement) {
        if (security != null) security.remove(securityRequirement);
    }

    @Override public List<Server> getServers() { return servers; }
    @Override public void setServers(List<Server> servers) { this.servers = servers; }

    @Override
    public Operation addServer(Server server) {
        if (server == null) return this;
        if (servers == null) servers = new ArrayList<>();
        servers.add(server);
        return this;
    }

    @Override
    public void removeServer(Server server) {
        if (servers != null) servers.remove(server);
    }
}

