package io.vidocq.grimm.internal.model;

import org.eclipse.microprofile.openapi.models.PathItem;
import org.eclipse.microprofile.openapi.models.callbacks.Callback;

import java.util.LinkedHashMap;
import java.util.Map;

public class CallbackImpl extends AbstractExtensibleRef<Callback> implements Callback {

    private Map<String, PathItem> pathItems;

    @Override protected String resolveComponentPrefix() { return "#/components/callbacks/"; }

    @Override
    public Callback addPathItem(String name, PathItem pathItem) {
        if (pathItem == null) return this;
        if (pathItems == null) pathItems = new LinkedHashMap<>();
        pathItems.put(name, pathItem);
        return this;
    }

    @Override
    public void removePathItem(String name) {
        if (pathItems != null) pathItems.remove(name);
    }

    @Override public Map<String, PathItem> getPathItems() { return pathItems; }
    @Override public void setPathItems(Map<String, PathItem> items) { this.pathItems = items; }
}

