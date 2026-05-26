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
        if (mediaTypes == null) mediaTypes = new LinkedHashMap<>();
        mediaTypes.put(name, mediaType);
        return this;
    }

    @Override
    public void removeMediaType(String name) {
        if (mediaTypes != null) mediaTypes.remove(name);
    }

    @Override
    public Map<String, MediaType> getMediaTypes() { return mediaTypes; }

    @Override
    public void setMediaTypes(Map<String, MediaType> mediaTypes) { this.mediaTypes = mediaTypes; }
}

