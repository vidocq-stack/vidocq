package io.vidocq.grimm.internal.model;

import org.eclipse.microprofile.openapi.models.media.Content;
import org.eclipse.microprofile.openapi.models.media.MediaType;

import java.util.LinkedHashMap;
import java.util.Map;

public class ContentImpl implements Content {

    private Map<String, MediaType> mediaTypes;

    @Override
    public Content addMediaType(String name, MediaType mediaType) {
        if (mediaType == null) return this;
        mediaTypes = ModelCollections.copyOnWriteMap(mediaTypes);
        mediaTypes.put(name, mediaType);
        return this;
    }

    @Override
    public void removeMediaType(String name) {
        if (mediaTypes != null) {
            mediaTypes = ModelCollections.copyOnWriteMap(mediaTypes);
            mediaTypes.remove(name);
        }
    }

    @Override
    public Map<String, MediaType> getMediaTypes() { return ModelCollections.immutableMapView(mediaTypes); }

    @Override
    public void setMediaTypes(Map<String, MediaType> mediaTypes) { this.mediaTypes = ModelCollections.mutableMap(mediaTypes); }
}

