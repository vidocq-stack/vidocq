package io.vidocq.grimm.internal.model;

import org.eclipse.microprofile.openapi.models.examples.Example;

public class ExampleImpl extends AbstractExtensibleRef<Example> implements Example {

    private String summary;
    private String description;
    private Object value;
    private String externalValue;

    @Override protected String resolveComponentPrefix() { return "#/components/examples/"; }

    @Override public String getSummary() { return summary; }
    @Override public void setSummary(String summary) { this.summary = summary; }

    @Override public String getDescription() { return description; }
    @Override public void setDescription(String description) { this.description = description; }

    @Override public Object getValue() { return value; }
    @Override public void setValue(Object value) { this.value = value; }

    @Override public String getExternalValue() { return externalValue; }
    @Override public void setExternalValue(String externalValue) { this.externalValue = externalValue; }
}

