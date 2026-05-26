package io.vidocq.grimm.internal.model;

import org.eclipse.microprofile.openapi.models.media.XML;

public class XMLImpl extends AbstractExtensible<XML> implements XML {

    private String name;
    private String namespace;
    private String prefix;
    private Boolean attribute;
    private Boolean wrapped;

    @Override public String getName() { return name; }
    @Override public void setName(String name) { this.name = name; }

    @Override public String getNamespace() { return namespace; }
    @Override public void setNamespace(String namespace) { this.namespace = namespace; }

    @Override public String getPrefix() { return prefix; }
    @Override public void setPrefix(String prefix) { this.prefix = prefix; }

    @Override public Boolean getAttribute() { return attribute; }
    @Override public void setAttribute(Boolean attribute) { this.attribute = attribute; }

    @Override public Boolean getWrapped() { return wrapped; }
    @Override public void setWrapped(Boolean wrapped) { this.wrapped = wrapped; }
}

