package io.vidocq.grimm.internal.model;

import org.eclipse.microprofile.openapi.models.headers.Header;
import org.eclipse.microprofile.openapi.models.media.Encoding;

import java.util.LinkedHashMap;
import java.util.Map;

public class EncodingImpl extends AbstractExtensible<Encoding> implements Encoding {

    private String contentType;
    private Map<String, Header> headers;
    private Style style;
    private Boolean explode;
    private Boolean allowReserved;

    @Override public String getContentType() { return contentType; }
    @Override public void setContentType(String contentType) { this.contentType = contentType; }

    @Override public Map<String, Header> getHeaders() { return ModelCollections.immutableMapView(headers); }
    @Override public void setHeaders(Map<String, Header> headers) { this.headers = ModelCollections.mutableMap(headers); }

    @Override
    public Encoding addHeader(String key, Header header) {
        if (header == null) return this;
        headers = ModelCollections.copyOnWriteMap(headers);
        headers.put(key, header);
        return this;
    }

    @Override
    public void removeHeader(String key) {
        if (headers != null) {
            headers = ModelCollections.copyOnWriteMap(headers);
            headers.remove(key);
        }
    }

    @Override public Style getStyle() { return style; }
    @Override public void setStyle(Style style) { this.style = style; }

    @Override public Boolean getExplode() { return explode; }
    @Override public void setExplode(Boolean explode) { this.explode = explode; }

    @Override public Boolean getAllowReserved() { return allowReserved; }
    @Override public void setAllowReserved(Boolean allowReserved) { this.allowReserved = allowReserved; }
}

