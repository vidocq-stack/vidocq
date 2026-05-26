package io.vidocq.grimm.internal.model;

import org.eclipse.microprofile.openapi.models.ExternalDocumentation;
import org.eclipse.microprofile.openapi.models.tags.Tag;

public class TagImpl extends AbstractExtensible<Tag> implements Tag {

    private String name;
    private String description;
    private ExternalDocumentation externalDocs;

    @Override public String getName() { return name; }
    @Override public void setName(String name) { this.name = name; }

    @Override public String getDescription() { return description; }
    @Override public void setDescription(String description) { this.description = description; }

    @Override public ExternalDocumentation getExternalDocs() { return externalDocs; }
    @Override public void setExternalDocs(ExternalDocumentation externalDocs) { this.externalDocs = externalDocs; }
}

