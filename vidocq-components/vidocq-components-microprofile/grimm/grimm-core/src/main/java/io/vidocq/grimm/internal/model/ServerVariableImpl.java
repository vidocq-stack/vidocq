package io.vidocq.grimm.internal.model;

import org.eclipse.microprofile.openapi.models.servers.ServerVariable;

import java.util.ArrayList;
import java.util.List;

public class ServerVariableImpl extends AbstractExtensible<ServerVariable> implements ServerVariable {

    private List<String> enumeration;
    private String defaultValue;
    private String description;

    @Override public List<String> getEnumeration() { return enumeration; }
    @Override public void setEnumeration(List<String> enumeration) { this.enumeration = enumeration; }

    @Override
    public ServerVariable addEnumeration(String value) {
        if (value == null) return this;
        if (enumeration == null) enumeration = new ArrayList<>();
        enumeration.add(value);
        return this;
    }

    @Override
    public void removeEnumeration(String value) {
        if (enumeration != null) enumeration.remove(value);
    }

    @Override public String getDefaultValue() { return defaultValue; }
    @Override public void setDefaultValue(String defaultValue) { this.defaultValue = defaultValue; }

    @Override public String getDescription() { return description; }
    @Override public void setDescription(String description) { this.description = description; }
}

