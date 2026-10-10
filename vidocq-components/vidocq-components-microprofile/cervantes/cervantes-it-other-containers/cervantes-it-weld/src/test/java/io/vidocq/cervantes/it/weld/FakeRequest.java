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
package io.vidocq.cervantes.it.weld;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.SecurityContext;
import java.lang.reflect.Proxy;
import java.util.Map;

/**
 * The part of a JAX-RS request the authentication filter reads and writes. Weld SE has no Jakarta
 * REST runtime, so the test hands the filter bean this stand-in.
 */
final class FakeRequest {

    private final String authorization;
    private SecurityContext securityContext;
    private boolean aborted;

    FakeRequest(String authorization) {
        this.authorization = authorization;
    }

    SecurityContext securityContext() {
        return securityContext;
    }

    boolean aborted() {
        return aborted;
    }

    ContainerRequestContext context() {
        return (ContainerRequestContext) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {ContainerRequestContext.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getHeaderString" -> HttpHeaders.AUTHORIZATION.equalsIgnoreCase((String) args[0]) ? authorization : null;
                    case "getCookies" -> Map.of();
                    case "getSecurityContext" -> securityContext;
                    case "setSecurityContext" -> {
                        securityContext = (SecurityContext) args[0];
                        yield null;
                    }
                    case "abortWith" -> {
                        aborted = true;
                        yield null;
                    }
                    case "toString" -> "FakeRequest";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
