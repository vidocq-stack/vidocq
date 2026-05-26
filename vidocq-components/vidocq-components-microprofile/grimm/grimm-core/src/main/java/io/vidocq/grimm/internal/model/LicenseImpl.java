package io.vidocq.grimm.internal.model;

import org.eclipse.microprofile.openapi.models.info.License;

public class LicenseImpl extends AbstractExtensible<License> implements License {

    private String name;
    private String identifier;
    private String url;

    @Override public String getName() { return name; }
    @Override public void setName(String name) { this.name = name; }

    @Override public String getIdentifier() { return identifier; }
    @Override public void setIdentifier(String identifier) { this.identifier = identifier; }

    @Override public String getUrl() { return url; }
    @Override public void setUrl(String url) { this.url = url; }
}

