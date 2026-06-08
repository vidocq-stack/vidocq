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

import org.eclipse.microprofile.openapi.models.servers.Server;
import org.eclipse.microprofile.openapi.models.servers.ServerVariable;

import java.util.LinkedHashMap;
import java.util.Map;

public class ServerImpl extends AbstractExtensible<Server> implements Server {

    private String url;
    private String description;
    private Map<String, ServerVariable> variables;

    @Override public String getUrl() { return url; }
    @Override public void setUrl(String url) { this.url = url; }

    @Override public String getDescription() { return description; }
    @Override public void setDescription(String description) { this.description = description; }

    @Override public Map<String, ServerVariable> getVariables() { return ModelCollections.immutableMapView(variables); }
    @Override public void setVariables(Map<String, ServerVariable> variables) { this.variables = ModelCollections.mutableMap(variables); }

    @Override
    public Server addVariable(String variableName, ServerVariable variable) {
        if (variable == null) return this;
        variables = ModelCollections.copyOnWriteMap(variables);
        variables.put(variableName, variable);
        return this;
    }

    @Override
    public void removeVariable(String variableName) {
        if (variables != null) {
            variables = ModelCollections.copyOnWriteMap(variables);
            variables.remove(variableName);
        }
    }
}

