package io.vidocq.grimm.internal.model;

import org.eclipse.microprofile.openapi.models.Operation;
import org.eclipse.microprofile.openapi.models.PathItem;
import org.eclipse.microprofile.openapi.models.parameters.Parameter;
import org.eclipse.microprofile.openapi.models.servers.Server;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class PathItemImpl extends AbstractExtensibleRef<PathItem> implements PathItem {

    private String summary;
    private String description;
    private Operation get;
    private Operation put;
    private Operation post;
    private Operation delete;
    private Operation options;
    private Operation head;
    private Operation patch;
    private Operation trace;
    private List<Server> servers;
    private List<Parameter> parameters;

    @Override protected String resolveComponentPrefix() { return "#/components/pathItems/"; }

    @Override public String getSummary() { return summary; }
    @Override public void setSummary(String summary) { this.summary = summary; }

    @Override public String getDescription() { return description; }
    @Override public void setDescription(String description) { this.description = description; }

    @Override public Operation getGET() { return get; }
    @Override public void setGET(Operation get) { this.get = get; }

    @Override public Operation getPUT() { return put; }
    @Override public void setPUT(Operation put) { this.put = put; }

    @Override public Operation getPOST() { return post; }
    @Override public void setPOST(Operation post) { this.post = post; }

    @Override public Operation getDELETE() { return delete; }
    @Override public void setDELETE(Operation delete) { this.delete = delete; }

    @Override public Operation getOPTIONS() { return options; }
    @Override public void setOPTIONS(Operation options) { this.options = options; }

    @Override public Operation getHEAD() { return head; }
    @Override public void setHEAD(Operation head) { this.head = head; }

    @Override public Operation getPATCH() { return patch; }
    @Override public void setPATCH(Operation patch) { this.patch = patch; }

    @Override public Operation getTRACE() { return trace; }
    @Override public void setTRACE(Operation trace) { this.trace = trace; }

    @Override
    public Map<HttpMethod, Operation> getOperations() {
        Map<HttpMethod, Operation> ops = new LinkedHashMap<>();
        if (get != null)     ops.put(HttpMethod.GET, get);
        if (put != null)     ops.put(HttpMethod.PUT, put);
        if (post != null)    ops.put(HttpMethod.POST, post);
        if (delete != null)  ops.put(HttpMethod.DELETE, delete);
        if (options != null) ops.put(HttpMethod.OPTIONS, options);
        if (head != null)    ops.put(HttpMethod.HEAD, head);
        if (patch != null)   ops.put(HttpMethod.PATCH, patch);
        if (trace != null)   ops.put(HttpMethod.TRACE, trace);
        return ops;
    }

    @Override
    public void setOperation(HttpMethod httpMethod, Operation operation) {
        switch (httpMethod) {
            case GET     -> setGET(operation);
            case PUT     -> setPUT(operation);
            case POST    -> setPOST(operation);
            case DELETE  -> setDELETE(operation);
            case OPTIONS -> setOPTIONS(operation);
            case HEAD    -> setHEAD(operation);
            case PATCH   -> setPATCH(operation);
            case TRACE   -> setTRACE(operation);
        }
    }

    @Override public List<Server> getServers() { return servers; }
    @Override public void setServers(List<Server> servers) { this.servers = servers; }

    @Override
    public PathItem addServer(Server server) {
        if (server == null) return this;
        if (servers == null) servers = new ArrayList<>();
        servers.add(server);
        return this;
    }

    @Override
    public void removeServer(Server server) {
        if (servers != null) servers.remove(server);
    }

    @Override public List<Parameter> getParameters() { return parameters; }
    @Override public void setParameters(List<Parameter> parameters) { this.parameters = parameters; }

    @Override
    public PathItem addParameter(Parameter parameter) {
        if (parameter == null) return this;
        if (parameters == null) parameters = new ArrayList<>();
        parameters.add(parameter);
        return this;
    }

    @Override
    public void removeParameter(Parameter parameter) {
        if (parameters != null) parameters.remove(parameter);
    }
}

