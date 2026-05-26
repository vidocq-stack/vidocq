package io.vidocq.grimm.internal.model;

import org.eclipse.microprofile.openapi.models.media.Discriminator;

import java.util.LinkedHashMap;
import java.util.Map;

public class DiscriminatorImpl implements Discriminator {

    private String propertyName;
    private Map<String, String> mapping;

    @Override public String getPropertyName() { return propertyName; }
    @Override public void setPropertyName(String propertyName) { this.propertyName = propertyName; }

    @Override
    public Discriminator addMapping(String name, String value) {
        if (value == null) return this;
        mapping = ModelCollections.copyOnWriteMap(mapping);
        mapping.put(name, value);
        return this;
    }

    @Override
    public void removeMapping(String name) {
        if (mapping != null) {
            mapping = ModelCollections.copyOnWriteMap(mapping);
            mapping.remove(name);
        }
    }

    @Override public Map<String, String> getMapping() { return ModelCollections.immutableMapView(mapping); }
    @Override public void setMapping(Map<String, String> mapping) { this.mapping = ModelCollections.mutableMap(mapping); }
}

