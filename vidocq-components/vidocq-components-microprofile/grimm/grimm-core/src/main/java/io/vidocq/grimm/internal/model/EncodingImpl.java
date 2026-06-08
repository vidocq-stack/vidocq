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

import org.eclipse.microprofile.openapi.models.headers.Header;
import org.eclipse.microprofile.openapi.models.media.Encoding;

import java.util.LinkedHashMap;
import java.util.Map;

public class EncodingImpl extends AbstractExtensible<Encoding> implements Encoding {

    private String contentType;
    private Map<String, Header> headers;
    private Style style;
    private Boolean explode;
    private Boolean allowReserved;

    @Override public String getContentType() { return contentType; }
    @Override public void setContentType(String contentType) { this.contentType = contentType; }

    @Override public Map<String, Header> getHeaders() { return ModelCollections.immutableMapView(headers); }
    @Override public void setHeaders(Map<String, Header> headers) { this.headers = ModelCollections.mutableMap(headers); }

    @Override
    public Encoding addHeader(String key, Header header) {
        if (header == null) return this;
        headers = ModelCollections.copyOnWriteMap(headers);
        headers.put(key, header);
        return this;
    }

    @Override
    public void removeHeader(String key) {
        if (headers != null) {
            headers = ModelCollections.copyOnWriteMap(headers);
            headers.remove(key);
        }
    }

    @Override public Style getStyle() { return style; }
    @Override public void setStyle(Style style) { this.style = style; }

    @Override public Boolean getExplode() { return explode; }
    @Override public void setExplode(Boolean explode) { this.explode = explode; }

    @Override public Boolean getAllowReserved() { return allowReserved; }
    @Override public void setAllowReserved(Boolean allowReserved) { this.allowReserved = allowReserved; }
}

