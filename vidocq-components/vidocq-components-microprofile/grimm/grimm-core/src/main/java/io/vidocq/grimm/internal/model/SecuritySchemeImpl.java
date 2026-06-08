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

import org.eclipse.microprofile.openapi.models.security.OAuthFlows;
import org.eclipse.microprofile.openapi.models.security.SecurityScheme;

public class SecuritySchemeImpl extends AbstractExtensibleRef<SecurityScheme> implements SecurityScheme {

    private Type type;
    private String description;
    private String name;
    private In in;
    private String scheme;
    private String bearerFormat;
    private OAuthFlows flows;
    private String openIdConnectUrl;

    @Override protected String resolveComponentPrefix() { return "#/components/securitySchemes/"; }

    @Override public Type getType() { return type; }
    @Override public void setType(Type type) { this.type = type; }

    @Override public String getDescription() { return description; }
    @Override public void setDescription(String description) { this.description = description; }

    @Override public String getName() { return name; }
    @Override public void setName(String name) { this.name = name; }

    @Override public In getIn() { return in; }
    @Override public void setIn(In in) { this.in = in; }

    @Override public String getScheme() { return scheme; }
    @Override public void setScheme(String scheme) { this.scheme = scheme; }

    @Override public String getBearerFormat() { return bearerFormat; }
    @Override public void setBearerFormat(String bearerFormat) { this.bearerFormat = bearerFormat; }

    @Override public OAuthFlows getFlows() { return flows; }
    @Override public void setFlows(OAuthFlows flows) { this.flows = flows; }

    @Override public String getOpenIdConnectUrl() { return openIdConnectUrl; }
    @Override public void setOpenIdConnectUrl(String openIdConnectUrl) { this.openIdConnectUrl = openIdConnectUrl; }
}

