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

import jakarta.security.enterprise.AuthenticationStatus;
import jakarta.security.enterprise.CallerPrincipal;
import jakarta.security.enterprise.authentication.mechanism.http.RememberMe;
import jakarta.security.enterprise.credential.RememberMeCredential;
import jakarta.security.enterprise.identitystore.CredentialValidationResult;
import jakarta.security.enterprise.identitystore.RememberMeIdentityStore;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;

import javax.security.auth.Subject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code @RememberMe} (Jakarta Security 4.0, section 3.8.2): a cookie stands for a login the caller already made. */
class RememberMeSupportTest {

    /** A store that remembers the callers it gave a token to. */
    private static final class Store implements RememberMeIdentityStore {
        final Map<String, CredentialValidationResult> tokens = new HashMap<>();
        final List<String> removed = new ArrayList<>();

        @Override
        public CredentialValidationResult validate(RememberMeCredential credential) {
            return tokens.getOrDefault(credential.getToken(), CredentialValidationResult.INVALID_RESULT);
        }

        @Override
        public String generateLoginToken(CallerPrincipal callerPrincipal, Set<String> groups) {
            String token = "token-" + (tokens.size() + 1);
            tokens.put(token, new CredentialValidationResult(callerPrincipal, groups));
            return token;
        }

        @Override
        public void removeLoginToken(String token) {
            removed.add(token);
            tokens.remove(token);
        }
    }

    private final Fakes.Exchange exchange = new Fakes.Exchange();
    private final Store store = new Store();
    private final List<String> logins = new ArrayList<>();
    private int asked;

    private DefaultHttpMessageContext context() {
        var info = new Fakes.Info(Fakes.request(new HashMap<>(), new ArrayList<>(), Map.of(), exchange),
                Fakes.response(new ArrayList<>(), exchange), new HashMap<>());
        return new DefaultHttpMessageContext(info, new Subject(), callbacks -> logins.add("login"));
    }

    private static RememberMeSupport.Settings settings(boolean rememberMe) {
        return new RememberMeSupport.Settings("JREMEMBERMEID", 3600, true, true, () -> rememberMe);
    }

    private AuthenticationStatus request(DefaultHttpMessageContext context, RememberMeSupport.Settings settings,
                                         boolean mechanismLogsIn) throws Exception {
        return RememberMeSupport.validateRequest(context.getRequest(), context.getResponse(), context, store,
                () -> settings, () -> {
                    asked++;
                    return mechanismLogsIn ? context.notifyContainerAboutLogin("reza", Set.of("foo", "bar"))
                            : context.doNothing();
                });
    }

    /** A mechanism that keeps the annotation's defaults. */
    @RememberMe
    static final class Defaults {
    }

    /** A mechanism whose attributes are all expressions; the literal ones say the opposite. */
    @RememberMe(cookieMaxAgeSeconds = 1, cookieMaxAgeSecondsExpression = "${1800}",
            cookieSecureOnly = true, cookieSecureOnlyExpression = "#{false}",
            cookieHttpOnly = true, cookieHttpOnlyExpression = "${false}",
            cookieName = "literal", isRememberMe = false, isRememberMeExpression = "#{memorize}")
    static final class Expressions {
    }

    @Test
    void theAnnotationDefaultsAreTheSettings() {
        var settings = RememberMeSupport.resolve(Defaults.class.getAnnotation(RememberMe.class), Map.of());

        assertEquals("JREMEMBERMEID", settings.cookieName());
        assertEquals(86400, settings.maxAgeSeconds());
        assertTrue(settings.secureOnly());
        assertTrue(settings.httpOnly());
        assertTrue(settings.rememberMe().getAsBoolean());
    }

    @Test
    void anExpressionOverridesTheLiteralAttributeItBelongsTo() {
        var settings = RememberMeSupport.resolve(Expressions.class.getAnnotation(RememberMe.class),
                Map.of("memorize", true));

        assertEquals(1800, settings.maxAgeSeconds());
        assertFalse(settings.secureOnly());
        assertFalse(settings.httpOnly());
        assertEquals("literal", settings.cookieName());
        assertTrue(settings.rememberMe().getAsBoolean());
    }

    @Test
    void theRememberMeExpressionSeesTheVariablesOfTheCall() {
        var variables = new HashMap<String, Object>(Map.of("memorize", false));
        var settings = RememberMeSupport.resolve(Expressions.class.getAnnotation(RememberMe.class), variables);

        assertFalse(settings.rememberMe().getAsBoolean());
    }

    @Test
    void aCallerWhoAskedToBeRememberedGetsACookieWithTheTokenOfTheStore() throws Exception {
        assertEquals(AuthenticationStatus.SUCCESS, request(context(), settings(true), true));

        assertEquals(1, exchange.cookiesSet.size());
        Cookie cookie = exchange.cookiesSet.getFirst();
        assertEquals("JREMEMBERMEID", cookie.getName());
        assertEquals("token-1", cookie.getValue());
        assertEquals(3600, cookie.getMaxAge());
        assertTrue(cookie.isHttpOnly());
        assertTrue(cookie.getSecure());
        assertEquals("/app", cookie.getPath());
    }

    @Test
    void aCallerWhoDidNotAskIsNotRemembered() throws Exception {
        request(context(), settings(false), true);

        assertTrue(exchange.cookiesSet.isEmpty());
        assertTrue(store.tokens.isEmpty());
    }

    @Test
    void nobodyIsRememberedWhenTheMechanismDidNotLogAnyoneIn() throws Exception {
        assertEquals(AuthenticationStatus.NOT_DONE, request(context(), settings(true), false));

        assertTrue(exchange.cookiesSet.isEmpty());
        assertTrue(store.tokens.isEmpty());
    }

    @Test
    void aValidCookieLogsTheCallerInWithoutAskingTheMechanism() throws Exception {
        request(context(), settings(true), true);
        exchange.cookies.add(new Cookie("JREMEMBERMEID", "token-1"));
        exchange.cookiesSet.clear();
        logins.clear();

        var next = context();
        assertEquals(AuthenticationStatus.SUCCESS, request(next, settings(true), false));

        assertEquals(1, asked, "the mechanism was not asked again");
        assertEquals("reza", next.getCallerPrincipal().getName());
        assertEquals(Set.of("foo", "bar"), next.getGroups());
        assertEquals(List.of("login"), logins);
    }

    @Test
    void anUnknownCookieIsForgottenAndTheMechanismIsAsked() throws Exception {
        exchange.cookies.add(new Cookie("JREMEMBERMEID", "forged"));

        assertEquals(AuthenticationStatus.NOT_DONE, request(context(), settings(true), false));

        assertEquals(1, asked);
        assertEquals(1, exchange.cookiesSet.size());
        assertEquals(0, exchange.cookiesSet.getFirst().getMaxAge(), "the client is told to drop the cookie");
    }

    @Test
    void aLogoutRemovesTheTokenAndTheCookie() {
        exchange.cookies.add(new Cookie("JREMEMBERMEID", "token-1"));
        var context = context();

        RememberMeSupport.cleanSubject(context.getRequest(), context.getResponse(), store, settings(true));

        assertEquals(List.of("token-1"), store.removed);
        assertEquals(0, exchange.cookiesSet.getFirst().getMaxAge());
    }

    @Test
    void aLogoutWithoutAResponseStillRemovesTheTokenSoTheCookieIsDeadAtTheNextRequest() throws Exception {
        request(context(), settings(true), true);
        exchange.cookies.add(new Cookie("JREMEMBERMEID", "token-1"));
        exchange.cookiesSet.clear();

        RememberMeSupport.cleanSubject(context().getRequest(), null, store, settings(true));

        assertEquals(List.of("token-1"), store.removed);
        assertTrue(exchange.cookiesSet.isEmpty(), "there is no response to carry the cookie");
        assertEquals(AuthenticationStatus.NOT_DONE, request(context(), settings(true), false),
                "the dead token logs nobody in");
    }

    @Test
    void aLogoutWithoutCookieChangesNothing() {
        var context = context();

        RememberMeSupport.cleanSubject(context.getRequest(), context.getResponse(), store, settings(true));

        assertTrue(store.removed.isEmpty());
        assertFalse(!exchange.cookiesSet.isEmpty());
    }
}
