package io.vidocq.grimm.internal.model;

import org.eclipse.microprofile.openapi.models.Extensible;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Base class providing the {@link Extensible} contract for all model POJOs.
 *
 * @param <T> the self-type, returned by {@link #addExtension(String, Object)}
 */
public abstract class AbstractExtensible<T extends Extensible<T>> implements Extensible<T> {

    private Map<String, Object> extensions;

    @Override
    public Map<String, Object> getExtensions() {
        return ModelCollections.immutableMapView(extensions);
    }

    @Override
    public void setExtensions(Map<String, Object> extensions) {
        this.extensions = ModelCollections.mutableMap(extensions);
    }

    @Override
    @SuppressWarnings("unchecked")
    public T addExtension(String name, Object value) {
        if (value == null) {
            return (T) this;
        }
        extensions = ModelCollections.copyOnWriteMap(extensions);
        extensions.put(name, value);
        return (T) this;
    }

    @Override
    public void removeExtension(String name) {
        if (extensions != null) {
            extensions = ModelCollections.copyOnWriteMap(extensions);
            extensions.remove(name);
        }
    }
}

