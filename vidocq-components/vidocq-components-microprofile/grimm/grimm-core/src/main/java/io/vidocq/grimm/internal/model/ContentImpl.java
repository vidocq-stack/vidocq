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

import org.eclipse.microprofile.openapi.models.media.Content;
import org.eclipse.microprofile.openapi.models.media.MediaType;

import java.util.Map;

public class ContentImpl implements Content {

    private Map<String, MediaType> mediaTypes;

    @Override
    public Content addMediaType(String name, MediaType mediaType) {
        if (mediaType == null) return this;
        mediaTypes = ModelCollections.copyOnWriteMap(mediaTypes);
        mediaTypes.put(name, mediaType);
        return this;
    }

    @Override
    public void removeMediaType(String name) {
        if (mediaTypes != null) {
            mediaTypes = ModelCollections.copyOnWriteMap(mediaTypes);
            mediaTypes.remove(name);
        }
    }

    @Override
    public Map<String, MediaType> getMediaTypes() { return ModelCollections.immutableMapView(mediaTypes); }

    @Override
    public void setMediaTypes(Map<String, MediaType> mediaTypes) { this.mediaTypes = ModelCollections.mutableMap(mediaTypes); }
}

