package io.vidocq.grimm.internal.model;

import org.eclipse.microprofile.openapi.models.media.Content;
import org.eclipse.microprofile.openapi.models.parameters.RequestBody;

public class RequestBodyImpl extends AbstractExtensibleRef<RequestBody> implements RequestBody {

    private String description;
    private Content content;
    private Boolean required;

    @Override protected String resolveComponentPrefix() { return "#/components/requestBodies/"; }

    @Override public String getDescription() { return description; }
    @Override public void setDescription(String description) { this.description = description; }

    @Override public Content getContent() { return content; }
    @Override public void setContent(Content content) { this.content = content; }

    @Override public Boolean getRequired() { return required; }
    @Override public void setRequired(Boolean required) { this.required = required; }
}

