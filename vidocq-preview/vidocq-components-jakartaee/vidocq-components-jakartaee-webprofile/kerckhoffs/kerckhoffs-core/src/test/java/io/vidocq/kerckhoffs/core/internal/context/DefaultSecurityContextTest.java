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
package io.vidocq.kerckhoffs.core.internal.context;

import io.vidocq.kerckhoffs.core.internal.mechanism.DefaultHttpMessageContext;
import jakarta.security.enterprise.AuthenticationStatus;
import jakarta.security.enterprise.authentication.mechanism.http.AuthenticationParameters;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.security.Principal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The security context of Jakarta Security 4.0 (section 3.10): it answers for the caller of the request being served,
 * and for nobody when there is no request. What it cannot answer it refuses instead of guessing.
 */
class DefaultSecurityContextTest {

    private record Named(String name) implements Principal {
        @Override public String getName() { return name; }
    }

    private static final class Special implements Principal {
        @Override public String getName() { return "special"; }
    }

    private static HttpServletRequest request(Principal caller, Set<String> roles) {
        return (HttpServletRequest) Proxy.newProxyInstance(HttpServletRequest.class.getClassLoader(),
                new Class<?>[] {HttpServletRequest.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getUserPrincipal" -> caller;
                    case "isUserInRole" -> roles.contains((String) args[0]);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static DefaultSecurityContext contextOf(HttpServletRequest request) {
        return new DefaultSecurityContext(() -> Optional.ofNullable(request));
    }

    @Test
    void withoutARequestNobodyIsTheCaller() {
        var context = contextOf(null);

        assertNull(context.getCallerPrincipal());
        assertFalse(context.isCallerInRole("admin"));
        assertTrue(context.getPrincipalsByType(Principal.class).isEmpty());
    }

    @Test
    void anAnonymousRequestHasNoCaller() {
        var context = contextOf(request(null, Set.of("admin")));

        assertNull(context.getCallerPrincipal());
        assertTrue(context.getPrincipalsByType(Principal.class).isEmpty());
    }

    @Test
    void theCallerIsTheOneOfTheRequest() {
        var context = contextOf(request(new Named("reza"), Set.of("foo")));

        assertEquals("reza", context.getCallerPrincipal().getName());
        assertTrue(context.isCallerInRole("foo"));
        assertFalse(context.isCallerInRole("bar"));
    }

    @Test
    void aRoleIsRefusedWhenItIsNull() {
        assertFalse(contextOf(request(new Named("reza"), Set.of("foo"))).isCallerInRole(null));
    }

    @Test
    void thePrincipalsAreFilteredByType() {
        var caller = new Special();
        var context = contextOf(request(caller, Set.of()));

        assertEquals(Set.of(caller), context.getPrincipalsByType(Special.class));
        assertTrue(context.getPrincipalsByType(Named.class).isEmpty());
    }

    @Test
    void whatIsNotKnownYetIsRefusedNotGuessed() {
        var context = contextOf(request(new Named("reza"), Set.of()));

        assertThrows(UnsupportedOperationException.class, context::getAllDeclaredCallerRoles);
        assertFalse(context.hasAccessToWebResource("/protected"), "access is denied while nothing can decide it");
    }

    /** A request whose {@code authenticate} answers {@code outcome}; the attributes it holds are recorded. */
    private static HttpServletRequest authenticating(Map<String, Object> attributes, Object outcome) {
        return (HttpServletRequest) Proxy.newProxyInstance(HttpServletRequest.class.getClassLoader(),
                new Class<?>[] {HttpServletRequest.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "setAttribute" -> attributes.put((String) args[0], args[1]);
                    case "removeAttribute" -> attributes.remove((String) args[0]);
                    case "authenticate" -> {
                        attributes.put("seen", attributes.get(DefaultHttpMessageContext.PARAMETERS));
                        if (outcome instanceof Exception failure) throw failure;
                        yield outcome;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static HttpServletResponse response(boolean committed, int status) {
        return (HttpServletResponse) Proxy.newProxyInstance(HttpServletResponse.class.getClassLoader(),
                new Class<?>[] {HttpServletResponse.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "isCommitted" -> committed;
                    case "getStatus" -> status;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private final Map<String, Object> attributes = new HashMap<>();
    private final AuthenticationParameters parameters = new AuthenticationParameters();

    @Test
    void aCallerTheMechanismLetInIsAuthenticated() {
        var request = authenticating(attributes, true);

        var status = contextOf(request).authenticate(request, response(false, 200), parameters);

        assertEquals(AuthenticationStatus.SUCCESS, status);
        assertEquals(parameters, attributes.get("seen"), "the mechanism could read the parameters");
        assertFalse(attributes.containsKey(DefaultHttpMessageContext.PARAMETERS), "and they do not outlive the call");
    }

    @Test
    void aMechanismThatRefusedIsAFailure() {
        var request = authenticating(attributes, false);

        assertEquals(AuthenticationStatus.SEND_FAILURE,
                contextOf(request).authenticate(request, response(false, 200), parameters));
        assertFalse(attributes.containsKey(DefaultHttpMessageContext.PARAMETERS));
    }

    @Test
    void aMechanismThatFailedIsAFailureNotAnException() {
        var request = authenticating(attributes, new ServletException("the authentication mechanism failed"));

        assertEquals(AuthenticationStatus.SEND_FAILURE,
                contextOf(request).authenticate(request, response(false, 200), parameters));
        assertFalse(attributes.containsKey(DefaultHttpMessageContext.PARAMETERS), "even when the call failed");
    }

    @Test
    void aResponseThatContinuesElsewhereIsAContinue() {
        var request = authenticating(attributes, false);

        assertEquals(AuthenticationStatus.SEND_CONTINUE,
                contextOf(request).authenticate(request, response(true, 302), parameters));
    }

    @Test
    void anErrorResponseIsAFailure() {
        var request = authenticating(attributes, false);

        assertEquals(AuthenticationStatus.SEND_FAILURE,
                contextOf(request).authenticate(request, response(true, 401), parameters));
    }
}
