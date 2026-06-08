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

