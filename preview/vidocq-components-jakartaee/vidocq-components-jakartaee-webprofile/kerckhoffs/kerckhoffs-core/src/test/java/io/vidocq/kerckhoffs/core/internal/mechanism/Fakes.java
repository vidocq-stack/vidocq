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
package io.vidocq.kerckhoffs.core.internal.mechanism;

import jakarta.security.auth.message.MessageInfo;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;

/** Servlet objects and a {@code MessageInfo} for the tests of the mechanism: no mock library, only what is asked of them. */
final class Fakes {

    private Fakes() { }

    /** A {@code MessageInfo} that holds what it is given. */
    static final class Info implements MessageInfo {
        private Object request;
        private Object response;
        private final Map<String, Object> map;

        Info(Object request, Object response, Map<String, Object> map) {
            this.request = request;
            this.response = response;
            this.map = map;
        }

        @Override public Object getRequestMessage() { return request; }
        @Override public Object getResponseMessage() { return response; }
        @Override public void setRequestMessage(Object request) { this.request = request; }
        @Override public void setResponseMessage(Object response) { this.response = response; }
        @Override public Map<String, Object> getMap() { return map; }
    }

    /** A request with attributes, the context path {@code /app}, and a dispatcher that records its forwards. */
    static HttpServletRequest request(Map<String, Object> attributes, List<String> forwarded) {
        return request(attributes, forwarded, Map.of());
    }

    /** The same, with the request headers {@code headers}. */
    static HttpServletRequest request(Map<String, Object> attributes, List<String> forwarded,
                                      Map<String, String> headers) {
        return (HttpServletRequest) Proxy.newProxyInstance(Fakes.class.getClassLoader(),
                new Class<?>[] {HttpServletRequest.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getHeader" -> headers.get((String) args[0]);
                    case "getAttribute" -> attributes.get((String) args[0]);
                    case "setAttribute" -> attributes.put((String) args[0], args[1]);
                    case "getContextPath" -> "/app";
                    case "getRequestDispatcher" -> dispatcher((String) args[0], forwarded);
                    default -> null;
                });
    }

    private static RequestDispatcher dispatcher(String path, List<String> forwarded) {
        return (RequestDispatcher) Proxy.newProxyInstance(Fakes.class.getClassLoader(),
                new Class<?>[] {RequestDispatcher.class}, (proxy, method, args) -> {
                    if ("forward".equals(method.getName())) forwarded.add(path);
                    return null;
                });
    }

    /** A response that records the redirects, errors and statuses it is asked for. */
    static HttpServletResponse response(List<String> sent) {
        return (HttpServletResponse) Proxy.newProxyInstance(Fakes.class.getClassLoader(),
                new Class<?>[] {HttpServletResponse.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "sendRedirect" -> sent.add("redirect " + args[0]);
                        case "sendError" -> sent.add("error " + args[0]);
                        case "setStatus" -> sent.add("status " + args[0]);
                        case "setHeader" -> sent.add("header " + args[0] + ": " + args[1]);
                        case "encodeRedirectURL" -> { return args[0]; }
                        default -> {
                            // Every other call is accepted and ignored: the tests ask only what they check.
                        }
                    }
                    return null;
                });
    }
}
