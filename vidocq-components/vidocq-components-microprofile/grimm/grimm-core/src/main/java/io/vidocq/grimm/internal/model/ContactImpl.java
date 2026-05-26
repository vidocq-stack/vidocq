package io.vidocq.grimm.internal.model;

import org.eclipse.microprofile.openapi.models.info.Contact;

public class ContactImpl extends AbstractExtensible<Contact> implements Contact {

    private String name;
    private String url;
    private String email;

    @Override public String getName() { return name; }
    @Override public void setName(String name) { this.name = name; }

    @Override public String getUrl() { return url; }
    @Override public void setUrl(String url) { this.url = url; }

    @Override public String getEmail() { return email; }
    @Override public void setEmail(String email) { this.email = email; }
}

