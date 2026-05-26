package io.vidocq.grimm.internal.model;

import org.eclipse.microprofile.openapi.models.headers.Header;
import org.eclipse.microprofile.openapi.models.links.Link;
import org.eclipse.microprofile.openapi.models.media.Content;
import org.eclipse.microprofile.openapi.models.responses.APIResponse;

import java.util.LinkedHashMap;
import java.util.Map;

public class APIResponseImpl extends AbstractExtensibleRef<APIResponse> implements APIResponse {

    private String description;
    private Map<String, Header> headers;
    private Content content;
    private Map<String, Link> links;

    @Override protected String resolveComponentPrefix() { return "#/components/responses/"; }

    @Override public String getDescription() { return description; }
    @Override public void setDescription(String description) { this.description = description; }

    @Override public Map<String, Header> getHeaders() { return headers; }
    @Override public void setHeaders(Map<String, Header> headers) { this.headers = headers; }

    @Override
    public APIResponse addHeader(String name, Header header) {
        if (header == null) return this;
        if (headers == null) headers = new LinkedHashMap<>();
        headers.put(name, header);
        return this;
    }

    @Override
    public void removeHeader(String name) {
        if (headers != null) headers.remove(name);
    }

    @Override public Content getContent() { return content; }
    @Override public void setContent(Content content) { this.content = content; }

    @Override public Map<String, Link> getLinks() { return links; }
    @Override public void setLinks(Map<String, Link> links) { this.links = links; }

    @Override
    public APIResponse addLink(String name, Link link) {
        if (link == null) return this;
        if (links == null) links = new LinkedHashMap<>();
        links.put(name, link);
        return this;
    }

    @Override
    public void removeLink(String name) {
        if (links != null) links.remove(name);
    }
}

