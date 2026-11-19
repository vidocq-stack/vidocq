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

import jakarta.interceptor.InvocationContext;
import jakarta.security.enterprise.AuthenticationStatus;
import jakarta.security.enterprise.CallerPrincipal;
import jakarta.security.enterprise.authentication.mechanism.http.HttpAuthenticationMechanism;
import jakarta.security.enterprise.authentication.mechanism.http.HttpMessageContext;
import jakarta.security.enterprise.authentication.mechanism.http.RememberMe;
import jakarta.security.enterprise.credential.RememberMeCredential;
import jakarta.security.enterprise.identitystore.CredentialValidationResult;
import jakarta.security.enterprise.identitystore.RememberMeIdentityStore;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The interceptor puts {@code @RememberMe} around {@code validateRequest} and {@code cleanSubject}. */
class RememberMeInterceptorTest {

    /** The mechanism the interceptor is bound to; the expression calls one of its methods. */
    @RememberMe(isRememberMeExpression = "#{self.wantsToBeRemembered()}")
    public static final class Mechanism {
        public Boolean wantsToBeRemembered() {
            return true;
        }
    }

    private final List<String> calls = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    private final List<Cookie> cookiesSet = new ArrayList<>();
    private final List<Cookie> cookiesSent = new ArrayList<>();

    private final RememberMeIdentityStore store = new RememberMeIdentityStore() {
        @Override
        public CredentialValidationResult validate(RememberMeCredential credential) {
            return "known".equals(credential.getToken())
                    ? new CredentialValidationResult(new CallerPrincipal("reza"), Set.of("foo"))
                    : CredentialValidationResult.INVALID_RESULT;
        }

        @Override
        public String generateLoginToken(CallerPrincipal callerPrincipal, Set<String> groups) {
            events.add("generate " + callerPrincipal.getName());
            return "known";
        }

        @Override
        public void removeLoginToken(String token) {
            events.add("remove " + token);
        }
    };

    private final RememberMeInterceptor interceptor = new RememberMeInterceptor(() -> store);

    private HttpServletRequest request() {
        return (HttpServletRequest) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {HttpServletRequest.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getCookies" -> cookiesSent.isEmpty() ? null : cookiesSent.toArray(new Cookie[0]);
                    case "getContextPath" -> "/app";
                    default -> null;
                });
    }

    private HttpServletResponse response() {
        return (HttpServletResponse) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {HttpServletResponse.class}, (proxy, method, args) -> {
                    if ("addCookie".equals(method.getName())) {
                        cookiesSet.add((Cookie) args[0]);
                    }
                    return null;
                });
    }

    /** A context that records the logins it is told about, and whose caller is the one of the last login. */
    private HttpMessageContext context() {
        return (HttpMessageContext) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {HttpMessageContext.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "notifyContainerAboutLogin" -> {
                        events.add("login");
                        yield AuthenticationStatus.SUCCESS;
                    }
                    case "getCallerPrincipal" -> events.contains("mechanism") ? new CallerPrincipal("reza") : null;
                    case "getGroups" -> Set.of("foo");
                    case "doNothing" -> AuthenticationStatus.NOT_DONE;
                    default -> null;
                });
    }

    private InvocationContext invocation(String method, boolean mechanismLogsIn) throws NoSuchMethodException {
        return invocation(method, mechanismLogsIn, response());
    }

    private InvocationContext invocation(String method, boolean mechanismLogsIn, HttpServletResponse response)
            throws NoSuchMethodException {
        Method target = HttpAuthenticationMechanism.class.getMethod(method, HttpServletRequest.class,
                HttpServletResponse.class, HttpMessageContext.class);
        Object[] parameters = {request(), response, context()};
        return (InvocationContext) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {InvocationContext.class}, (proxy, m, args) -> switch (m.getName()) {
                    case "getParameters" -> parameters;
                    case "getMethod" -> target;
                    case "getTarget" -> new Mechanism();
                    case "getInterceptorBinding" -> Mechanism.class.getAnnotation(RememberMe.class);
                    case "proceed" -> {
                        calls.add(method);
                        if (mechanismLogsIn) {
                            events.add("mechanism");
                            yield AuthenticationStatus.SUCCESS;
                        }
                        yield AuthenticationStatus.NOT_DONE;
                    }
                    default -> null;
                });
    }

    @Test
    void aCallThatIsNotInterceptedGoesThrough() throws Exception {
        var result = interceptor.intercept(invocation("secureResponse", false));

        assertEquals(AuthenticationStatus.NOT_DONE, result);
        assertEquals(List.of("secureResponse"), calls);
        assertTrue(cookiesSet.isEmpty());
    }

    @Test
    void aCallerTheMechanismLogsInIsRememberedWhenTheExpressionOfTheMechanismSaysSo() throws Exception {
        var result = interceptor.intercept(invocation("validateRequest", true));

        assertEquals(AuthenticationStatus.SUCCESS, result);
        assertEquals(List.of("validateRequest"), calls);
        assertEquals(List.of("mechanism", "generate reza"), events);
        assertEquals("known", cookiesSet.getFirst().getValue());
    }

    @Test
    void aKnownCookieLogsTheCallerInWithoutAskingTheMechanism() throws Exception {
        cookiesSent.add(new Cookie("JREMEMBERMEID", "known"));

        var result = interceptor.intercept(invocation("validateRequest", false));

        assertEquals(AuthenticationStatus.SUCCESS, result);
        assertTrue(calls.isEmpty(), "the mechanism was not asked");
        assertEquals(List.of("login"), events);
    }

    @Test
    void aLogoutLetsTheMechanismCleanThenForgetsTheToken() throws Exception {
        cookiesSent.add(new Cookie("JREMEMBERMEID", "known"));

        interceptor.intercept(invocation("cleanSubject", false));

        assertEquals(List.of("cleanSubject"), calls);
        assertEquals(List.of("remove known"), events);
        assertEquals(0, cookiesSet.getFirst().getMaxAge());
    }

    @Test
    void aLogoutTheContainerSendsWithoutAResponseStillForgetsTheToken() throws Exception {
        cookiesSent.add(new Cookie("JREMEMBERMEID", "known"));

        interceptor.intercept(invocation("cleanSubject", false, null));

        assertEquals(List.of("cleanSubject"), calls);
        assertEquals(List.of("remove known"), events);
        assertTrue(cookiesSet.isEmpty());
    }
}
