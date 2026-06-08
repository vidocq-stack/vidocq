/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
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

