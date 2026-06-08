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

import org.eclipse.microprofile.openapi.models.servers.ServerVariable;

import java.util.ArrayList;
import java.util.List;

public class ServerVariableImpl extends AbstractExtensible<ServerVariable> implements ServerVariable {

    private List<String> enumeration;
    private String defaultValue;
    private String description;

    @Override public List<String> getEnumeration() { return ModelCollections.immutableListView(enumeration); }
    @Override public void setEnumeration(List<String> enumeration) { this.enumeration = ModelCollections.mutableList(enumeration); }

    @Override
    public ServerVariable addEnumeration(String value) {
        if (value == null) return this;
        enumeration = ModelCollections.copyOnWriteList(enumeration);
        enumeration.add(value);
        return this;
    }

    @Override
    public void removeEnumeration(String value) {
        if (enumeration != null) {
            enumeration = ModelCollections.copyOnWriteList(enumeration);
            enumeration.remove(value);
        }
    }

    @Override public String getDefaultValue() { return defaultValue; }
    @Override public void setDefaultValue(String defaultValue) { this.defaultValue = defaultValue; }

    @Override public String getDescription() { return description; }
    @Override public void setDescription(String description) { this.description = description; }
}

