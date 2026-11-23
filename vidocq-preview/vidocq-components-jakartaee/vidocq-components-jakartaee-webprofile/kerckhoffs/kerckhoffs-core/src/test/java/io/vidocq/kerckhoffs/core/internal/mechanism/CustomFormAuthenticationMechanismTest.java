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
import jakarta.security.enterprise.authentication.mechanism.http.AuthenticationParameters;
import jakarta.security.enterprise.credential.UsernamePasswordCredential;
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
 * The built-in custom FORM mechanism of {@code @CustomFormAuthenticationMechanismDefinition} (Jakarta Security 4.0,
 * sections 3.8.1 and 3.8.5): the login page belongs to the application, which hands the credential to
 * {@code SecurityContext.authenticate}. The refusals come first.
 */
class CustomFormAuthenticationMechanismTest {

    @InMemoryIdentityStoreDefinition({@Credentials(callerName = "reza", password = "secret1", groups = {"foo", "bar"})})
    private static final class Store { }

    private final Fakes.Exchange exchange = new Fakes.Exchange();
    private final Map<String, Object> attributes = new HashMap<>();
    private final List<String> sent = new ArrayList<>();
    private final List<String> forwarded = new ArrayList<>();
    private final List<String> logins = new ArrayList<>();
    private final Map<String, Object> policy = new HashMap<>();

    private final FormAuthenticationMechanism form = FormAuthenticationMechanism.customForm("/login.xhtml", true,
            () -> new DefaultIdentityStoreHandler(
                    List.of(InMemoryIdentityStore.of(Store.class.getAnnotation(InMemoryIdentityStoreDefinition.class)))));

    private AuthenticationStatus request(boolean mandatory) {
        policy.put(DefaultHttpMessageContext.MANDATORY, Boolean.toString(mandatory));
        var info = new Fakes.Info(Fakes.request(attributes, forwarded, Map.of(), exchange), Fakes.response(sent), policy);
        var context = new DefaultHttpMessageContext(info, new Subject(), cbs -> logins.add("login"));
        return form.validateRequest(context.getRequest(), context.getResponse(), context);
    }

    /** What {@code SecurityContext.authenticate} leaves in the request before it asks the mechanism. */
    private AuthenticationStatus authenticate(String caller, String password) {
        exchange.method = "POST";
        exchange.uri = "/app/login.xhtml";
        exchange.query = null;
        attributes.put(DefaultHttpMessageContext.PARAMETERS,
                AuthenticationParameters.withParams().credential(new UsernamePasswordCredential(caller, password)));
        try {
            return request(false);
        } finally {
            attributes.remove(DefaultHttpMessageContext.PARAMETERS);
        }
    }

    private void reset() {
        exchange.method = "GET";
        exchange.uri = "/app/servlet";
        exchange.query = null;
        exchange.parameters.clear();
    }

    @Test
    void aProtectedResourceOfNobodyIsForwardedToTheApplicationsLoginPage() {
        assertEquals(AuthenticationStatus.SEND_CONTINUE, request(true));

        assertEquals(List.of("/login.xhtml"), forwarded);
        assertTrue(logins.isEmpty());
    }

    @Test
    void aWrongPasswordFailsAndLogsNobodyIn() {
        assertEquals(AuthenticationStatus.SEND_FAILURE, authenticate("reza", "wrong"));

        assertTrue(logins.isEmpty());
        assertTrue(forwarded.isEmpty(), "the application's page tells the caller, not an error page");
        assertFalse(exchange.session.containsKey(FormAuthenticationMechanism.IDENTITY));
    }

    @Test
    void anUnknownCallerFailsToo() {
        assertEquals(AuthenticationStatus.SEND_FAILURE, authenticate("nobody", "secret1"));

        assertTrue(logins.isEmpty());
    }

    @Test
    void aPostToJSecurityCheckIsNoLoginOfACustomForm() {
        exchange.method = "POST";
        exchange.uri = "/app/j_security_check";
        exchange.parameters.put("j_username", "reza");
        exchange.parameters.put("j_password", "secret1");

        assertEquals(AuthenticationStatus.NOT_DONE, request(false));

        assertTrue(logins.isEmpty());
        assertFalse(exchange.session.containsKey(FormAuthenticationMechanism.IDENTITY));
    }

    @Test
    void validCredentialsLogTheCallerInOnTheSameRequest() {
        assertEquals(AuthenticationStatus.SUCCESS, authenticate("reza", "secret1"));

        assertEquals(List.of("login"), logins);
        assertTrue(sent.isEmpty(), "no requested page: the application's page goes on");
        assertTrue(exchange.session.containsKey(FormAuthenticationMechanism.IDENTITY));
        assertTrue(exchange.sessionEvents.contains("changeSessionId"), "a login renews the session id");
    }

    @Test
    void validCredentialsGoBackToTheRequestedPage() {
        exchange.query = "a=b";
        request(true);

        assertEquals(AuthenticationStatus.SEND_CONTINUE, authenticate("reza", "secret1"));

        assertEquals(List.of("redirect /app/servlet?a=b"), sent);
    }

    @Test
    void theRememberedCallerIsLoggedInOnTheNextRequest() {
        authenticate("reza", "secret1");
        logins.clear();
        reset();

        assertEquals(AuthenticationStatus.SUCCESS, request(true));

        assertEquals(List.of("login"), logins);
        assertTrue(forwarded.isEmpty());
    }

    @Test
    void anAuthenticateCallWithoutCredentialOnAPublicPageDoesNothing() {
        attributes.put(DefaultHttpMessageContext.PARAMETERS, AuthenticationParameters.withParams());

        assertEquals(AuthenticationStatus.NOT_DONE, request(false));

        assertTrue(logins.isEmpty());
    }
}
