package io.vidocq.grimm.internal.model;

import org.eclipse.microprofile.openapi.models.ExternalDocumentation;

public class ExternalDocumentationImpl extends AbstractExtensible<ExternalDocumentation>
        implements ExternalDocumentation {

    private String description;
    private String url;

    @Override public String getDescription() { return description; }
    @Override public void setDescription(String description) { this.description = description; }

    @Override public String getUrl() { return url; }
    @Override public void setUrl(String url) { this.url = url; }
}

