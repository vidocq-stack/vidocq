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

import org.eclipse.microprofile.openapi.models.PathItem;
import org.eclipse.microprofile.openapi.models.Paths;

import java.util.Map;

public class PathsImpl extends AbstractExtensible<Paths> implements Paths {

    private Map<String, PathItem> pathItems;

    @Override
    public Paths addPathItem(String name, PathItem item) {
        if (item == null) return this;
        pathItems = ModelCollections.copyOnWriteMap(pathItems);
        pathItems.put(name, item);
        return this;
    }

    @Override
    public void removePathItem(String name) {
        if (pathItems != null) {
            pathItems = ModelCollections.copyOnWriteMap(pathItems);
            pathItems.remove(name);
        }
    }

    @Override
    public Map<String, PathItem> getPathItems() { return ModelCollections.immutableMapView(pathItems); }

    @Override
    public void setPathItems(Map<String, PathItem> items) { this.pathItems = ModelCollections.mutableMap(items); }
}

