package io.vidocq.grimm.internal.model;

import org.eclipse.microprofile.openapi.models.info.Contact;
import org.eclipse.microprofile.openapi.models.info.Info;
import org.eclipse.microprofile.openapi.models.info.License;

public class InfoImpl extends AbstractExtensible<Info> implements Info {

    private String title;
    private String summary;
    private String description;
    private String termsOfService;
    private Contact contact;
    private License license;
    private String version;

    @Override public String getTitle() { return title; }
    @Override public void setTitle(String title) { this.title = title; }

    @Override public String getSummary() { return summary; }
    @Override public void setSummary(String summary) { this.summary = summary; }

    @Override public String getDescription() { return description; }
    @Override public void setDescription(String description) { this.description = description; }

    @Override public String getTermsOfService() { return termsOfService; }
    @Override public void setTermsOfService(String termsOfService) { this.termsOfService = termsOfService; }

    @Override public Contact getContact() { return contact; }
    @Override public void setContact(Contact contact) { this.contact = contact; }

    @Override public License getLicense() { return license; }
    @Override public void setLicense(License license) { this.license = license; }

    @Override public String getVersion() { return version; }
    @Override public void setVersion(String version) { this.version = version; }
}

