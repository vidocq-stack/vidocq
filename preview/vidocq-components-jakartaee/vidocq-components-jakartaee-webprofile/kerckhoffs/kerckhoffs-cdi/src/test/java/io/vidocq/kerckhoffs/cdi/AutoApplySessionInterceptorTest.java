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
package io.vidocq.kerckhoffs.cdi;

import io.vidocq.kerckhoffs.core.internal.mechanism.AutoApplySessionSupport;
import jakarta.interceptor.InvocationContext;
import jakarta.security.enterprise.AuthenticationStatus;
import jakarta.security.enterprise.authentication.mechanism.http.HttpAuthenticationMechanism;
import jakarta.security.enterprise.authentication.mechanism.http.HttpMessageContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The interceptor of {@code @AutoApplySession} wraps two calls of the mechanism and lets every other one through;
 * what it does with the session is decided by {@link AutoApplySessionSupport}, tested on its own.
 */
class AutoApplySessionInterceptorTest {

    private final Map<String, Object> session = new HashMap<>();
    private final List<String> calls = new ArrayList<>();
    private final AutoApplySessionInterceptor interceptor = new AutoApplySessionInterceptor();

    private HttpServletRequest request() {
        HttpSession httpSession = (HttpSession) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {HttpSession.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getAttribute" -> session.get((String) args[0]);
                    case "removeAttribute" -> session.remove((String) args[0]);
                    case "setAttribute" -> session.put((String) args[0], args[1]);
                    default -> null;
                });
        return (HttpServletRequest) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {HttpServletRequest.class}, (proxy, method, args) ->
                        "getSession".equals(method.getName()) ? httpSession : null);
    }

    private static HttpMessageContext context() {
        return (HttpMessageContext) Proxy.newProxyInstance(AutoApplySessionInterceptorTest.class.getClassLoader(),
                new Class<?>[] {HttpMessageContext.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "isAuthenticationRequest" -> false;
                    case "doNothing" -> AuthenticationStatus.NOT_DONE;
                    default -> null;
                });
    }

    private InvocationContext invocation(String method, Object... parameters) throws NoSuchMethodException {
        Method target = HttpAuthenticationMechanism.class.getMethod(method, HttpServletRequest.class,
                HttpServletResponse.class, HttpMessageContext.class);
        return (InvocationContext) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {InvocationContext.class}, (proxy, m, args) -> switch (m.getName()) {
                    case "getParameters" -> parameters;
                    case "getMethod" -> target;
                    case "proceed" -> {
                        calls.add(method);
                        yield AuthenticationStatus.NOT_DONE;
                    }
                    default -> null;
                });
    }

    @Test
    void aCallThatIsNotInterceptedGoesThrough() throws Exception {
        var result = interceptor.intercept(invocation("secureResponse", request(), null, context()));

        assertEquals(AuthenticationStatus.NOT_DONE, result);
        assertEquals(List.of("secureResponse"), calls);
    }

    @Test
    void aValidationOfNobodyAsksTheMechanism() throws Exception {
        var result = interceptor.intercept(invocation("validateRequest", request(), null, context()));

        assertEquals(AuthenticationStatus.NOT_DONE, result);
        assertEquals(List.of("validateRequest"), calls);
        assertTrue(session.isEmpty(), "nobody is kept");
    }

    @Test
    void aLogoutLetsTheMechanismCleanThenForgetsTheCaller() throws Exception {
        session.put(AutoApplySessionSupport.IDENTITY, "kept");

        interceptor.intercept(invocation("cleanSubject", request(), null, context()));

        assertEquals(List.of("cleanSubject"), calls);
        assertFalse(session.containsKey(AutoApplySessionSupport.IDENTITY));
    }
}
