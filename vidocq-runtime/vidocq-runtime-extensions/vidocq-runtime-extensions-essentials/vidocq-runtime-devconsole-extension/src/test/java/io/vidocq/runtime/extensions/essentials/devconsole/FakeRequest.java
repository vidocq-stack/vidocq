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

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * A request as Chappe hands it to the console's handler, mounted at the root of its listener.
 *
 * @param method the method
 * @param path   the path, such as {@code /api/snapshot}
 * @param host        the {@code Host} header, or {@code null} for none
 * @param queryParams the query parameters, such as {@code since}
 * @param others      the headers besides {@code Host}, by name
 * @param content     the body
 */
record FakeRequest(HttpMethod method, String path, String host, Map<String, String> queryParams,
                   Map<String, String> others, byte[] content) implements Request {

    FakeRequest(HttpMethod method, String path, String host, Map<String, String> queryParams) {
        this(method, path, host, queryParams, Map.of(), new byte[0]);
    }

    FakeRequest(HttpMethod method, String path, String host) {
        this(method, path, host, Map.of());
    }

    /**
     * A {@code POST} with headers besides {@code Host} and a body.
     *
     * @param path    the path
     * @param host    the {@code Host} header, or {@code null}
     * @param headers the other headers, by name; a {@code null} value leaves the header out
     * @param body    the body, UTF-8
     */
    static FakeRequest post(String path, String host, Map<String, String> headers, String body) {
        return new FakeRequest(HttpMethod.POST, path, host, Map.of(), headers,
                body.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public InetSocketAddress remoteAddress() {
        return new InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 52814);
    }

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
        Headers.Builder headers = Headers.builder();
        if (host != null) {
            headers.add("Host", host);
        }
        others.forEach((name, value) -> {
            if (value != null) {
                headers.add(name, value);
            }
        });
        return headers.build();
    }

    @Override
    public Body body() {
        return Body.of(content);
    }

    @Override
    public Map<String, String> pathParams() {
        return Map.of();
    }
}
