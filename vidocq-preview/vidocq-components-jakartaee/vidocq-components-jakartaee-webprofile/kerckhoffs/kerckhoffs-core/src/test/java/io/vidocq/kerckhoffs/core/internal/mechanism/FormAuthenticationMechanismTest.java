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

import io.vidocq.kerckhoffs.core.internal.identitystore.DefaultIdentityStoreHandler;
import io.vidocq.kerckhoffs.core.internal.identitystore.InMemoryIdentityStore;
import jakarta.security.enterprise.AuthenticationStatus;
import jakarta.security.enterprise.identitystore.InMemoryIdentityStoreDefinition;
import jakarta.security.enterprise.identitystore.InMemoryIdentityStoreDefinition.Credentials;
import org.junit.jupiter.api.Test;

import javax.security.auth.Subject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The built-in FORM mechanism with its login to continue (Jakarta Security 4.0, sections 3.8.1.2 and 3.8.5). Nobody
 * is let in on a form that does not carry valid credentials; the refusals come first.
 */
class FormAuthenticationMechanismTest {

    @InMemoryIdentityStoreDefinition({@Credentials(callerName = "reza", password = "secret1", groups = {"foo", "bar"})})
    private static final class Store { }

    private final Fakes.Exchange exchange = new Fakes.Exchange();
    private final List<String> sent = new ArrayList<>();
    private final List<String> forwarded = new ArrayList<>();
    private final List<String> logins = new ArrayList<>();
    private final Map<String, Object> policy = new HashMap<>();

    private FormAuthenticationMechanism mechanism(String errorPage, boolean forward) {
        return new FormAuthenticationMechanism("/login", errorPage, forward, () -> new DefaultIdentityStoreHandler(
                List.of(InMemoryIdentityStore.of(Store.class.getAnnotation(InMemoryIdentityStoreDefinition.class)))));
    }

    private final FormAuthenticationMechanism form = mechanism("/login-error", true);

    private DefaultHttpMessageContext context(boolean mandatory) {
        policy.put(DefaultHttpMessageContext.MANDATORY, Boolean.toString(mandatory));
        var info = new Fakes.Info(Fakes.request(new HashMap<>(), forwarded, Map.of(), exchange), Fakes.response(sent), policy);
        return new DefaultHttpMessageContext(info, new Subject(), cbs -> logins.add("login"));
    }

    private AuthenticationStatus request(FormAuthenticationMechanism mechanism, boolean mandatory) {
        var context = context(mandatory);
        return mechanism.validateRequest(context.getRequest(), context.getResponse(), context);
    }

    private AuthenticationStatus request(boolean mandatory) {
        return request(form, mandatory);
    }

    private void submit(String user, String password) {
        exchange.method = "POST";
        exchange.uri = "/app/j_security_check";
        exchange.query = null;
        exchange.parameters.clear();
        if (user != null) exchange.parameters.put("j_username", user);
        if (password != null) exchange.parameters.put("j_password", password);
    }

    private void reset() {
        exchange.method = "GET";
        exchange.uri = "/app/servlet";
        exchange.query = null;
        exchange.parameters.clear();
    }

    @Test
    void aProtectedResourceOfNobodyIsForwardedToTheLoginPage() {
        assertEquals(AuthenticationStatus.SEND_CONTINUE, request(true));

        assertEquals(List.of("/login"), forwarded);
        assertTrue(logins.isEmpty());
    }

    @Test
    void anUnprotectedResourceOfNobodyIsLeftAlone() {
        assertEquals(AuthenticationStatus.NOT_DONE, request(false));

        assertTrue(forwarded.isEmpty());
        assertTrue(sent.isEmpty());
    }

    @Test
    void aWrongPasswordGoesToTheErrorPageAndLogsNobodyIn() {
        submit("reza", "wrong");

        assertEquals(AuthenticationStatus.SEND_CONTINUE, request(false));

        assertEquals(List.of("/login-error"), forwarded);
        assertEquals(List.of("GET"), exchange.forwardedAs, "the error page is a page to read, whatever was posted");
        assertTrue(logins.isEmpty());
        reset();
        assertEquals(AuthenticationStatus.SEND_CONTINUE, request(true), "still nobody: the login page again");
    }

    @Test
    void anUnknownCallerAndMissingFieldsGoToTheErrorPageToo() {
        submit("nobody", "secret1");
        assertEquals(AuthenticationStatus.SEND_CONTINUE, request(false));
        submit("reza", null);
        assertEquals(AuthenticationStatus.SEND_CONTINUE, request(false));
        submit(null, "secret1");
        assertEquals(AuthenticationStatus.SEND_CONTINUE, request(false));

        assertEquals(List.of("/login-error", "/login-error", "/login-error"), forwarded);
        assertTrue(logins.isEmpty());
    }

    @Test
    void withoutErrorPageAFailedLoginIsUnauthorized() {
        submit("reza", "wrong");

        assertEquals(AuthenticationStatus.SEND_FAILURE, request(mechanism("", true), false));

        assertEquals(List.of("status 401"), sent);
    }

    @Test
    void credentialsAreReadOnlyFromAPost() {
        submit("reza", "secret1");
        exchange.method = "GET";

        assertEquals(AuthenticationStatus.NOT_DONE, request(false), "a GET that carries credentials proves nothing");

        assertFalse(exchange.session.containsKey(FormAuthenticationMechanism.IDENTITY));
    }

    @Test
    void validCredentialsRememberTheCallerAndGoBackToTheRequestedPage() {
        exchange.uri = "/app/servlet";
        exchange.query = "a=b";
        request(true);
        submit("reza", "secret1");

        assertEquals(AuthenticationStatus.SEND_CONTINUE, request(false));

        assertEquals(List.of("redirect /app/servlet?a=b"), sent);
        assertTrue(exchange.session.containsKey(FormAuthenticationMechanism.IDENTITY));
        assertTrue(exchange.sessionEvents.contains("changeSessionId"), "a login renews the session id");
    }

    @Test
    void withoutAPageRequestedTheLoginGoesBackToTheApplication() {
        submit("reza", "secret1");

        request(false);

        assertEquals(List.of("redirect /app/"), sent);
    }

    @Test
    void theRememberedCallerIsLoggedInOnTheNextRequestWithItsGroups() {
        submit("reza", "secret1");
        request(false);
        reset();

        assertEquals(AuthenticationStatus.SUCCESS, request(true));

        assertEquals(List.of("login"), logins);
        assertTrue(forwarded.isEmpty());
    }

    @Test
    void aLogoutForgetsTheCaller() {
        submit("reza", "secret1");
        request(false);
        reset();
        var context = context(true);

        form.cleanSubject(context.getRequest(), context.getResponse(), context);

        assertFalse(exchange.session.containsKey(FormAuthenticationMechanism.IDENTITY));
        assertEquals(AuthenticationStatus.SEND_CONTINUE, request(true), "the login page again");
    }

    @Test
    void theLoginPageCanBeReachedByARedirectInsteadOfAForward() {
        assertEquals(AuthenticationStatus.SEND_CONTINUE, request(mechanism("/login-error", false), true));

        assertEquals(List.of("redirect /app/login"), sent);
        assertTrue(forwarded.isEmpty());
    }
}
