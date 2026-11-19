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

import org.eclipse.microprofile.openapi.models.links.Link;
import org.eclipse.microprofile.openapi.models.servers.Server;

import java.util.Map;

public class LinkImpl extends AbstractExtensibleRef<Link> implements Link {

    private Server server;
    private String operationRef;
    private Object requestBody;
    private String operationId;
    private Map<String, Object> parameters;
    private String description;

    @Override protected String resolveComponentPrefix() { return "#/components/links/"; }

    @Override public Server getServer() { return server; }
    @Override public void setServer(Server server) { this.server = server; }

    @Override public String getOperationRef() { return operationRef; }
    @Override public void setOperationRef(String operationRef) { this.operationRef = operationRef; }

    @Override public Object getRequestBody() { return requestBody; }
    @Override public void setRequestBody(Object requestBody) { this.requestBody = requestBody; }

    @Override public String getOperationId() { return operationId; }
    @Override public void setOperationId(String operationId) { this.operationId = operationId; }

    @Override public Map<String, Object> getParameters() { return ModelCollections.immutableMapView(parameters); }
    @Override public void setParameters(Map<String, Object> parameters) { this.parameters = ModelCollections.mutableMap(parameters); }

    @Override
    public Link addParameter(String name, Object parameter) {
        if (parameter == null) return this;
        parameters = ModelCollections.copyOnWriteMap(parameters);
        parameters.put(name, parameter);
        return this;
    }

    @Override
    public void removeParameter(String name) {
        if (parameters != null) {
            parameters = ModelCollections.copyOnWriteMap(parameters);
            parameters.remove(name);
        }
    }

    @Override public String getDescription() { return description; }
    @Override public void setDescription(String description) { this.description = description; }
}

