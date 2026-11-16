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
package io.vidocq.runtime.extensions.jakartaee.web.kerckhoffs;

import jakarta.enterprise.inject.Instance;
import jakarta.interceptor.InvocationContext;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** The extension, and the two beans of its module that hand the work to Kerckhoffs. */
class KerckhoffsBeansTest {

    @Test
    void theExtensionRunsBeforeTheServletExtension() {
        var extension = new KerckhoffsSecurityExtension();

        assertEquals("kerckhoffs-security", extension.name());
        assertEquals(580, extension.priority());
    }

    @Test
    @SuppressWarnings("unchecked")
    void theStartCheckAcceptsAContainerWithoutIdentityStores() {
        Instance<jakarta.security.enterprise.identitystore.IdentityStore> none =
                (Instance<jakarta.security.enterprise.identitystore.IdentityStore>) Proxy.newProxyInstance(
                        Instance.class.getClassLoader(), new Class<?>[] {Instance.class},
                        (proxy, method, args) -> switch (method.getName()) {
                            case "handles" -> List.of();
                            default -> throw new UnsupportedOperationException(method.getName());
                        });

        assertDoesNotThrow(() -> new VidocqIdentityStoreStartCheck().check(null, none));
    }

    @Test
    void theInterceptorLeavesTheOtherCallsOfAMechanismAlone() throws Exception {
        var other = Object.class.getMethod("toString");
        InvocationContext invocation = (InvocationContext) Proxy.newProxyInstance(
                InvocationContext.class.getClassLoader(), new Class<?>[] {InvocationContext.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getParameters" -> new Object[0];
                    case "getMethod" -> other;
                    case "proceed" -> "proceeded";
                    default -> throw new UnsupportedOperationException(method.getName());
                });

        assertEquals("proceeded", new VidocqAutoApplySessionInterceptor().intercept(invocation));
    }

    @Test
    @SuppressWarnings("unchecked")
    void theRememberMeInterceptorLeavesTheOtherCallsOfAMechanismAlone() throws Exception {
        var other = Object.class.getMethod("toString");
        InvocationContext invocation = (InvocationContext) Proxy.newProxyInstance(
                InvocationContext.class.getClassLoader(), new Class<?>[] {InvocationContext.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getParameters" -> new Object[0];
                    case "getMethod" -> other;
                    case "proceed" -> "proceeded";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        Instance<jakarta.security.enterprise.identitystore.RememberMeIdentityStore> noStore =
                (Instance<jakarta.security.enterprise.identitystore.RememberMeIdentityStore>) Proxy.newProxyInstance(
                        Instance.class.getClassLoader(), new Class<?>[] {Instance.class},
                        (proxy, method, args) -> {
                            throw new UnsupportedOperationException(method.getName());
                        });

        assertEquals("proceeded", new VidocqRememberMeInterceptor(noStore).intercept(invocation));
    }
}
