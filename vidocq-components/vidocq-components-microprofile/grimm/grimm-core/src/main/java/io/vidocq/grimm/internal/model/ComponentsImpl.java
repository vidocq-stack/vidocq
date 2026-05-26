package io.vidocq.grimm.internal.model;

import org.eclipse.microprofile.openapi.models.Components;
import org.eclipse.microprofile.openapi.models.PathItem;
import org.eclipse.microprofile.openapi.models.callbacks.Callback;
import org.eclipse.microprofile.openapi.models.examples.Example;
import org.eclipse.microprofile.openapi.models.headers.Header;
import org.eclipse.microprofile.openapi.models.links.Link;
import org.eclipse.microprofile.openapi.models.media.Schema;
import org.eclipse.microprofile.openapi.models.parameters.Parameter;
import org.eclipse.microprofile.openapi.models.parameters.RequestBody;
import org.eclipse.microprofile.openapi.models.responses.APIResponse;
import org.eclipse.microprofile.openapi.models.security.SecurityScheme;

import java.util.LinkedHashMap;
import java.util.Map;

public class ComponentsImpl extends AbstractExtensible<Components> implements Components {

    private Map<String, Schema> schemas;
    private Map<String, APIResponse> responses;
    private Map<String, Parameter> parameters;
    private Map<String, Example> examples;
    private Map<String, RequestBody> requestBodies;
    private Map<String, Header> headers;
    private Map<String, SecurityScheme> securitySchemes;
    private Map<String, Link> links;
    private Map<String, Callback> callbacks;
    private Map<String, PathItem> pathItems;

    // ── schemas ──
    @Override public Map<String, Schema> getSchemas() { return ModelCollections.immutableMapView(schemas); }
    @Override public void setSchemas(Map<String, Schema> schemas) { this.schemas = ModelCollections.mutableMap(schemas); }
    @Override public Components addSchema(String key, Schema schema) {
        if (schema == null) return this;
        schemas = ModelCollections.copyOnWriteMap(schemas);
        schemas.put(key, schema); return this; }
    @Override public void removeSchema(String key) { if (schemas != null) { schemas = ModelCollections.copyOnWriteMap(schemas); schemas.remove(key); } }

    // ── responses ──
    @Override public Map<String, APIResponse> getResponses() { return ModelCollections.immutableMapView(responses); }
    @Override public void setResponses(Map<String, APIResponse> responses) { this.responses = ModelCollections.mutableMap(responses); }
    @Override public Components addResponse(String key, APIResponse response) {
        if (response == null) return this;
        responses = ModelCollections.copyOnWriteMap(responses);
        responses.put(key, response); return this; }
    @Override public void removeResponse(String key) { if (responses != null) { responses = ModelCollections.copyOnWriteMap(responses); responses.remove(key); } }

    // ── parameters ──
    @Override public Map<String, Parameter> getParameters() { return ModelCollections.immutableMapView(parameters); }
    @Override public void setParameters(Map<String, Parameter> parameters) { this.parameters = ModelCollections.mutableMap(parameters); }
    @Override public Components addParameter(String key, Parameter parameter) {
        if (parameter == null) return this;
        parameters = ModelCollections.copyOnWriteMap(parameters);
        parameters.put(key, parameter); return this; }
    @Override public void removeParameter(String key) { if (parameters != null) { parameters = ModelCollections.copyOnWriteMap(parameters); parameters.remove(key); } }

    // ── examples ──
    @Override public Map<String, Example> getExamples() { return ModelCollections.immutableMapView(examples); }
    @Override public void setExamples(Map<String, Example> examples) { this.examples = ModelCollections.mutableMap(examples); }
    @Override public Components addExample(String key, Example example) {
        if (example == null) return this;
        examples = ModelCollections.copyOnWriteMap(examples);
        examples.put(key, example); return this; }
    @Override public void removeExample(String key) { if (examples != null) { examples = ModelCollections.copyOnWriteMap(examples); examples.remove(key); } }

    // ── requestBodies ──
    @Override public Map<String, RequestBody> getRequestBodies() { return ModelCollections.immutableMapView(requestBodies); }
    @Override public void setRequestBodies(Map<String, RequestBody> requestBodies) { this.requestBodies = ModelCollections.mutableMap(requestBodies); }
    @Override public Components addRequestBody(String key, RequestBody requestBody) {
        if (requestBody == null) return this;
        requestBodies = ModelCollections.copyOnWriteMap(requestBodies);
        requestBodies.put(key, requestBody); return this; }
    @Override public void removeRequestBody(String key) { if (requestBodies != null) { requestBodies = ModelCollections.copyOnWriteMap(requestBodies); requestBodies.remove(key); } }

    // ── headers ──
    @Override public Map<String, Header> getHeaders() { return ModelCollections.immutableMapView(headers); }
    @Override public void setHeaders(Map<String, Header> headers) { this.headers = ModelCollections.mutableMap(headers); }
    @Override public Components addHeader(String key, Header header) {
        if (header == null) return this;
        headers = ModelCollections.copyOnWriteMap(headers);
        headers.put(key, header); return this; }
    @Override public void removeHeader(String key) { if (headers != null) { headers = ModelCollections.copyOnWriteMap(headers); headers.remove(key); } }

    // ── securitySchemes ──
    @Override public Map<String, SecurityScheme> getSecuritySchemes() { return ModelCollections.immutableMapView(securitySchemes); }
    @Override public void setSecuritySchemes(Map<String, SecurityScheme> securitySchemes) { this.securitySchemes = ModelCollections.mutableMap(securitySchemes); }
    @Override public Components addSecurityScheme(String key, SecurityScheme securityScheme) {
        if (securityScheme == null) return this;
        securitySchemes = ModelCollections.copyOnWriteMap(securitySchemes);
        securitySchemes.put(key, securityScheme); return this; }
    @Override public void removeSecurityScheme(String key) { if (securitySchemes != null) { securitySchemes = ModelCollections.copyOnWriteMap(securitySchemes); securitySchemes.remove(key); } }

    // ── links ──
    @Override public Map<String, Link> getLinks() { return ModelCollections.immutableMapView(links); }
    @Override public void setLinks(Map<String, Link> links) { this.links = ModelCollections.mutableMap(links); }
    @Override public Components addLink(String key, Link link) {
        if (link == null) return this;
        links = ModelCollections.copyOnWriteMap(links);
        links.put(key, link); return this; }
    @Override public void removeLink(String key) { if (links != null) { links = ModelCollections.copyOnWriteMap(links); links.remove(key); } }

    // ── callbacks ──
    @Override public Map<String, Callback> getCallbacks() { return ModelCollections.immutableMapView(callbacks); }
    @Override public void setCallbacks(Map<String, Callback> callbacks) { this.callbacks = ModelCollections.mutableMap(callbacks); }
    @Override public Components addCallback(String key, Callback callback) {
        if (callback == null) return this;
        callbacks = ModelCollections.copyOnWriteMap(callbacks);
        callbacks.put(key, callback); return this; }
    @Override public void removeCallback(String key) { if (callbacks != null) { callbacks = ModelCollections.copyOnWriteMap(callbacks); callbacks.remove(key); } }

    // ── pathItems (MP OpenAPI 4.0+) ──
    @Override public Map<String, PathItem> getPathItems() { return ModelCollections.immutableMapView(pathItems); }
    @Override public void setPathItems(Map<String, PathItem> pathItems) { this.pathItems = ModelCollections.mutableMap(pathItems); }
    @Override public Components addPathItem(String name, PathItem pathItem) {
        if (pathItem == null) return this;
        pathItems = ModelCollections.copyOnWriteMap(pathItems);
        pathItems.put(name, pathItem); return this; }
    @Override public void removePathItem(String name) { if (pathItems != null) { pathItems = ModelCollections.copyOnWriteMap(pathItems); pathItems.remove(name); } }
}

