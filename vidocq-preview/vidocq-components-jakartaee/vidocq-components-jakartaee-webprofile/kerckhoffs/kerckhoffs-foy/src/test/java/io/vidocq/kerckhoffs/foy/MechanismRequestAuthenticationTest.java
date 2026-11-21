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

import io.vidocq.foy.spi.security.RequestAuthentication;
import jakarta.security.enterprise.AuthenticationStatus;
import jakarta.security.enterprise.authentication.mechanism.http.HttpAuthenticationMechanism;
import jakarta.security.enterprise.authentication.mechanism.http.HttpMessageContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An {@code HttpAuthenticationMechanism} in service on the request authentication hook of Foy (Jakarta Security 4.0,
 * section 2.5). The refusals come first: what the mechanism breaks, the request does not survive.
 */
class MechanismRequestAuthenticationTest {

    private static <T> T proxy(Class<T> type, List<String> calls) {
        return type.cast(Proxy.newProxyInstance(MechanismRequestAuthenticationTest.class.getClassLoader(),
                new Class<?>[] {type}, (p, method, args) -> {
                    calls.add(method.getName());
                    return null;
                }));
    }

    private final List<String> calls = new ArrayList<>();
    private final HttpServletRequest request = proxy(HttpServletRequest.class, calls);
    private final HttpServletResponse response = proxy(HttpServletResponse.class, calls);

    private static MechanismRequestAuthentication with(HttpAuthenticationMechanism mechanism) {
        return new MechanismRequestAuthentication(() -> mechanism);
    }

    private static HttpAuthenticationMechanism logging(String caller, String... groups) {
        return (req, res, context) -> context.notifyContainerAboutLogin(caller, Set.of(groups));
    }

    @Test
    void aMechanismThatFailsDoesNotSurviveTheRequest() {
        HttpAuthenticationMechanism broken = (req, res, context) -> {
            throw new IllegalStateException("boom");
        };

        assertThrows(ServletException.class, () -> with(broken).validateRequest(request, response, true));
    }

    @Test
    void aMechanismThatAnswersNothingDoesNotSurviveEither() {
        assertThrows(ServletException.class, () -> with((req, res, context) -> null).validateRequest(request, response, true));
    }

    @Test
    void aFailedLoginStopsTheRequest() throws Exception {
        var outcome = with((req, res, context) -> AuthenticationStatus.SEND_FAILURE).validateRequest(request, response, true);

        assertInstanceOf(RequestAuthentication.Outcome.ResponseSent.class, outcome);
    }

    @Test
    void aMechanismThatDoesNothingLeavesTheCallerAnonymous() throws Exception {
        var outcome = with((req, res, context) -> AuthenticationStatus.NOT_DONE).validateRequest(request, response, false);

        assertInstanceOf(RequestAuthentication.Outcome.Anonymous.class, outcome);
    }

    @Test
    void aCallerAndItsGroupsBecomeTheIdentityOfTheRequest() throws Exception {
        var outcome = with(logging("reza", "foo", "bar")).validateRequest(request, response, true);

        var found = assertInstanceOf(RequestAuthentication.Outcome.Authenticated.class, outcome);
        assertEquals("reza", found.identity().name());
        assertEquals(Set.of("foo", "bar"), found.identity().roles());
        assertEquals("HTTP", found.authType(), "the mechanism gave no authentication type");
    }

    @Test
    void theMechanismKnowsWhetherTheResourceIsProtected() throws Exception {
        var seen = new ArrayList<Boolean>();
        HttpAuthenticationMechanism spy = (req, res, context) -> {
            seen.add(context.isProtected());
            return AuthenticationStatus.NOT_DONE;
        };
        var adapter = with(spy);

        adapter.validateRequest(request, response, true);
        adapter.validateRequest(request, response, false);

        assertEquals(List.of(true, false), seen);
    }

    @Test
    void theMechanismForgetsTheCallerOnLogout() {
        var cleaned = new ArrayList<String>();
        HttpAuthenticationMechanism mechanism = new HttpAuthenticationMechanism() {
            @Override public AuthenticationStatus validateRequest(HttpServletRequest req, HttpServletResponse res,
                                                                  HttpMessageContext context) {
                return AuthenticationStatus.SUCCESS;
            }

            @Override public void cleanSubject(HttpServletRequest req, HttpServletResponse res, HttpMessageContext context) {
                cleaned.add("cleaned");
            }
        };

        with(mechanism).cleanSubject(request);

        assertTrue(cleaned.contains("cleaned"));
    }
}
