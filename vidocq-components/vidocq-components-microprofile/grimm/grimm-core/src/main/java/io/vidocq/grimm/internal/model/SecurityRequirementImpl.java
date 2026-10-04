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

import org.eclipse.microprofile.openapi.models.security.SecurityRequirement;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class SecurityRequirementImpl implements SecurityRequirement {

    private Map<String, List<String>> schemes;

    @Override
    public SecurityRequirement addScheme(String securitySchemeName, String scope) {
        initSchemes();
        List<String> scopes = new ArrayList<>();
        if (scope != null) scopes.add(scope);
        schemes.put(securitySchemeName, scopes);
        return this;
    }

    @Override
    public SecurityRequirement addScheme(String securitySchemeName, List<String> scopes) {
        initSchemes();
        schemes.put(securitySchemeName, scopes == null ? new ArrayList<>() : scopes);
        return this;
    }

    @Override
    public SecurityRequirement addScheme(String securitySchemeName) {
        initSchemes();
        schemes.put(securitySchemeName, new ArrayList<>());
        return this;
    }

    @Override
    public void removeScheme(String securitySchemeName) {
        if (schemes != null) {
            schemes = ModelCollections.copyOnWriteMap(schemes);
            schemes.remove(securitySchemeName);
        }
    }

    @Override
    public Map<String, List<String>> getSchemes() {
        return ModelCollections.immutableMapView(schemes);
    }
    @Override public void setSchemes(Map<String, List<String>> items) {
        this.schemes = ModelCollections.mutableMap(items);
    }

    private void initSchemes() {
        schemes = ModelCollections.copyOnWriteMap(schemes);
    }
}

