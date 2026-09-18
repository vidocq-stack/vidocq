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
package io.vidocq.runtime.extensions.essentials.devconsole;

import io.vidocq.chappe.api.Body;
import io.vidocq.chappe.api.Headers;
import io.vidocq.chappe.api.HttpMethod;
import io.vidocq.chappe.api.HttpVersion;
import io.vidocq.chappe.api.Request;

import java.net.URI;
import java.util.Map;

/**
 * A request as Chappe hands it to the console's handler, mounted at the root of its listener.
 *
 * @param method the method
 * @param path   the path, such as {@code /api/snapshot}
 * @param host   the {@code Host} header, or {@code null} for none
 */
record FakeRequest(HttpMethod method, String path, String host) implements Request {

    static FakeRequest get(String path, String host) {
        return new FakeRequest(HttpMethod.GET, path, host);
    }

    @Override
    public URI uri() {
        return URI.create(path);
    }

    @Override
    public String query() {
        return null;
    }

    @Override
    public HttpVersion version() {
        return HttpVersion.HTTP_1_1;
    }

    @Override
    public Headers headers() {
        return host == null ? Headers.empty() : Headers.of("Host", host);
    }

    @Override
    public Body body() {
        return Body.empty();
    }

    @Override
    public Map<String, String> pathParams() {
        return Map.of();
    }

    @Override
    public Map<String, String> queryParams() {
        return Map.of();
    }
}
