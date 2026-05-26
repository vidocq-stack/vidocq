package io.vidocq.grimm.internal.model;

import org.eclipse.microprofile.openapi.models.PathItem;
import org.eclipse.microprofile.openapi.models.Paths;

import java.util.LinkedHashMap;
import java.util.Map;

public class PathsImpl extends AbstractExtensible<Paths> implements Paths {

    private Map<String, PathItem> pathItems;

    @Override
    public Paths addPathItem(String name, PathItem item) {
        if (item == null) return this;
        if (pathItems == null) pathItems = new LinkedHashMap<>();
        pathItems.put(name, item);
        return this;
    }

    @Override
    public void removePathItem(String name) {
        if (pathItems != null) pathItems.remove(name);
    }

    @Override
    public Map<String, PathItem> getPathItems() { return pathItems; }

    @Override
    public void setPathItems(Map<String, PathItem> items) { this.pathItems = items; }
}

