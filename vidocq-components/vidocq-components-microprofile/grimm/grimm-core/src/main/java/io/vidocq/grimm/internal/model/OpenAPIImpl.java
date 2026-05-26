package io.vidocq.grimm.internal.model;

import org.eclipse.microprofile.openapi.models.Components;
import org.eclipse.microprofile.openapi.models.ExternalDocumentation;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.PathItem;
import org.eclipse.microprofile.openapi.models.Paths;
import org.eclipse.microprofile.openapi.models.info.Info;
import org.eclipse.microprofile.openapi.models.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.models.servers.Server;
import org.eclipse.microprofile.openapi.models.tags.Tag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class OpenAPIImpl extends AbstractExtensible<OpenAPI> implements OpenAPI {

    private String openapi;
    private Info info;
    private ExternalDocumentation externalDocs;
    private List<Server> servers;
    private List<SecurityRequirement> security;
    private List<Tag> tags;
    private Paths paths;
    private Map<String, PathItem> webhooks;
    private String jsonSchemaDialect;
    private Components components;

    @Override
    public String getOpenapi() {
        return openapi;
    }

    @Override
    public void setOpenapi(String openapi) {
        this.openapi = openapi;
    }

    @Override
    public Info getInfo() {
        return info;
    }

    @Override
    public void setInfo(Info info) {
        this.info = info;
    }

    @Override
    public ExternalDocumentation getExternalDocs() {
        return externalDocs;
    }

    @Override
    public void setExternalDocs(ExternalDocumentation externalDocs) {
        this.externalDocs = externalDocs;
    }

    @Override
    public List<Server> getServers() {
        return ModelCollections.immutableListView(servers);
    }

    @Override
    public void setServers(List<Server> servers) {
        this.servers = ModelCollections.mutableList(servers);
    }

    @Override
    public OpenAPI addServer(Server server) {
        if (server == null) {
            return this;
        }
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

    @Override
    public List<SecurityRequirement> getSecurity() {
        return ModelCollections.immutableListView(security);
    }

    @Override
    public void setSecurity(List<SecurityRequirement> security) {
        this.security = ModelCollections.mutableList(security);
    }

    @Override
    public OpenAPI addSecurityRequirement(SecurityRequirement securityRequirement) {
        if (securityRequirement == null) {
            return this;
        }
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

    @Override
    public List<Tag> getTags() {
        return ModelCollections.immutableListView(tags);
    }

    @Override
    public void setTags(List<Tag> tags) {
        this.tags = ModelCollections.mutableList(tags);
    }

    @Override
    public OpenAPI addTag(Tag tag) {
        if (tag == null) {
            return this;
        }
        tags = ModelCollections.copyOnWriteList(tags);
        tags.add(tag);
        return this;
    }

    @Override
    public void removeTag(Tag tag) {
        if (tags != null) {
            tags = ModelCollections.copyOnWriteList(tags);
            tags.remove(tag);
        }
    }

    @Override
    public Paths getPaths() {
        return paths;
    }

    @Override
    public void setPaths(Paths paths) {
        this.paths = paths;
    }

    @Override
    public Map<String, PathItem> getWebhooks() {
        return ModelCollections.immutableMapView(webhooks);
    }

    @Override
    public void setWebhooks(Map<String, PathItem> webhooks) {
        this.webhooks = ModelCollections.mutableMap(webhooks);
    }

    @Override
    public OpenAPI addWebhook(String name, PathItem webhook) {
        if (name == null || webhook == null) {
            return this;
        }
        webhooks = ModelCollections.copyOnWriteMap(webhooks);
        webhooks.put(name, webhook);
        return this;
    }

    @Override
    public void removeWebhook(String name) {
        if (webhooks != null) {
            webhooks = ModelCollections.copyOnWriteMap(webhooks);
            webhooks.remove(name);
        }
    }

    @Override
    public String getJsonSchemaDialect() {
        return jsonSchemaDialect;
    }

    @Override
    public void setJsonSchemaDialect(String jsonSchemaDialect) {
        this.jsonSchemaDialect = jsonSchemaDialect;
    }

    @Override
    public Components getComponents() {
        return components;
    }

    @Override
    public void setComponents(Components components) {
        this.components = components;
    }
}

