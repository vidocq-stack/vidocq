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
package io.vidocq.kerckhoffs.foy;

import io.vidocq.foy.spi.security.CurrentRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.security.Principal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The security context of a Foy application answers for the request Foy is serving, and for nobody otherwise. */
class SecurityContextCreatorTest {

    private static HttpServletRequest requestOf(String caller) {
        Principal principal = () -> caller;
        return (HttpServletRequest) Proxy.newProxyInstance(HttpServletRequest.class.getClassLoader(),
                new Class<?>[] {HttpServletRequest.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getUserPrincipal" -> principal;
                    case "isUserInRole" -> "admin".equals(args[0]);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    void outsideARequestNobodyIsTheCaller() {
        var context = new SecurityContextCreator().create(null, null);

        assertNull(context.getCallerPrincipal());
    }

    @Test
    void insideARequestTheCallerIsTheOneOfThatRequest() {
        var context = new SecurityContextCreator().create(null, null);

        CurrentRequest.callWhere(() -> requestOf("reza"), () -> {
            assertEquals("reza", context.getCallerPrincipal().getName());
            assertEquals(true, context.isCallerInRole("admin"));
            return null;
        });

        assertNull(context.getCallerPrincipal(), "the request is gone with its scope");
    }
}
