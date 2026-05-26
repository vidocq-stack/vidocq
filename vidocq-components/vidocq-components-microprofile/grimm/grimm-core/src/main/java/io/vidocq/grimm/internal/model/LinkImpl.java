package io.vidocq.grimm.internal.model;

import org.eclipse.microprofile.openapi.models.links.Link;
import org.eclipse.microprofile.openapi.models.servers.Server;

import java.util.LinkedHashMap;
import java.util.Map;

public class LinkImpl extends AbstractExtensibleRef<Link> implements Link {

    private Server server;
    private String operationRef;
    private Object requestBody;
    private String operationId;
    private Map<String, Object> parameters;
    private String description;

    @Override protected String resolveComponentPrefix() { return "#/components/links/"; }

    @Override public Server getServer() { return server; }
    @Override public void setServer(Server server) { this.server = server; }

    @Override public String getOperationRef() { return operationRef; }
    @Override public void setOperationRef(String operationRef) { this.operationRef = operationRef; }

    @Override public Object getRequestBody() { return requestBody; }
    @Override public void setRequestBody(Object requestBody) { this.requestBody = requestBody; }

    @Override public String getOperationId() { return operationId; }
    @Override public void setOperationId(String operationId) { this.operationId = operationId; }

    @Override public Map<String, Object> getParameters() { return parameters; }
    @Override public void setParameters(Map<String, Object> parameters) { this.parameters = parameters; }

    @Override
    public Link addParameter(String name, Object parameter) {
        if (parameter == null) return this;
        if (parameters == null) parameters = new LinkedHashMap<>();
        parameters.put(name, parameter);
        return this;
    }

    @Override
    public void removeParameter(String name) {
        if (parameters != null) parameters.remove(name);
    }

    @Override public String getDescription() { return description; }
    @Override public void setDescription(String description) { this.description = description; }
}

