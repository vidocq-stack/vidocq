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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The built-in BASIC mechanism (Jakarta Security 4.0, section 3.8.1.1). A request that does not prove who it is never
 * logs anybody in; the refusals come first.
 */
class BasicAuthenticationMechanismTest {

    @InMemoryIdentityStoreDefinition({@Credentials(callerName = "reza", password = "secret1", groups = {"foo", "bar"})})
    private static final class Store { }

    private final List<String> sent = new ArrayList<>();
    private final List<String> callbacks = new ArrayList<>();
    private final Map<String, Object> policy = new HashMap<>();
    private final BasicAuthenticationMechanism mechanism = new BasicAuthenticationMechanism("test realm",
            () -> new DefaultIdentityStoreHandler(List.of(
                    InMemoryIdentityStore.of(Store.class.getAnnotation(InMemoryIdentityStoreDefinition.class)))));

    private static String basic(String userColonPassword) {
        return "Basic " + Base64.getEncoder().encodeToString(userColonPassword.getBytes(StandardCharsets.UTF_8));
    }

    private AuthenticationStatus request(String authorization, boolean mandatory) {
        policy.put(DefaultHttpMessageContext.MANDATORY, Boolean.toString(mandatory));
        Map<String, String> headers = authorization == null ? Map.of() : Map.of("Authorization", authorization);
        var info = new Fakes.Info(Fakes.request(new HashMap<>(), new ArrayList<>(), headers), Fakes.response(sent), policy);
        var context = new DefaultHttpMessageContext(info, new Subject(), cbs -> callbacks.add("login"));
        return mechanism.validateRequest(context.getRequest(), context.getResponse(), context);
    }

    @Test
    void aProtectedResourceWithoutCredentialsIsChallenged() {
        assertEquals(AuthenticationStatus.SEND_FAILURE, request(null, true));

        assertEquals(List.of("header WWW-Authenticate: Basic realm=\"test realm\"", "status 401"), sent);
        assertTrue(callbacks.isEmpty());
    }

    @Test
    void aWrongPasswordIsChallengedNotLoggedIn() {
        assertEquals(AuthenticationStatus.SEND_FAILURE, request(basic("reza:wrong"), true));

        assertTrue(callbacks.isEmpty());
        assertTrue(sent.contains("status 401"));
    }

    @Test
    void anUnknownCallerIsChallengedToo() {
        assertEquals(AuthenticationStatus.SEND_FAILURE, request(basic("nobody:secret1"), true));

        assertTrue(callbacks.isEmpty());
    }

    @Test
    void aHeaderThatIsNotBasicOrNotWellFormedIsIgnored() {
        assertEquals(AuthenticationStatus.SEND_FAILURE, request("Bearer abc", true));
        assertEquals(AuthenticationStatus.SEND_FAILURE, request("Basic !!!not-base64!!!", true));
        assertEquals(AuthenticationStatus.SEND_FAILURE, request(basic("no-colon"), true));
        assertEquals(AuthenticationStatus.SEND_FAILURE, request("Basic", true));

        assertTrue(callbacks.isEmpty());
    }

    @Test
    void anUnprotectedResourceWithoutCredentialsIsLeftAlone() {
        assertEquals(AuthenticationStatus.NOT_DONE, request(null, false));

        assertTrue(sent.isEmpty(), "nothing is sent: the resource is public");
    }

    @Test
    void anUnprotectedResourceWithWrongCredentialsIsRefusedAllTheSame() {
        assertEquals(AuthenticationStatus.SEND_FAILURE, request(basic("reza:wrong"), false));
    }

    @Test
    void validCredentialsLogTheCallerIn() {
        assertEquals(AuthenticationStatus.SUCCESS, request(basic("reza:secret1"), true));

        assertEquals(List.of("login"), callbacks);
        assertTrue(sent.isEmpty());
    }

    @Test
    void aPasswordMayContainAColon() {
        assertEquals(AuthenticationStatus.SEND_FAILURE, request(basic("reza:secret:1"), true));
    }

    @Test
    void theSchemeIsCaseInsensitive() {
        assertEquals(AuthenticationStatus.SUCCESS, request("basic " + basic("reza:secret1").substring(6), true));
    }

    @Test
    void theRealmIsQuotedAndCannotBreakTheChallenge() {
        var tricky = new BasicAuthenticationMechanism("a\"b\r\nX: y", () -> new DefaultIdentityStoreHandler(List.of()));
        policy.put(DefaultHttpMessageContext.MANDATORY, "true");
        var info = new Fakes.Info(Fakes.request(new HashMap<>(), new ArrayList<>()), Fakes.response(sent), policy);
        var context = new DefaultHttpMessageContext(info, new Subject(), cbs -> { });

        tricky.validateRequest(context.getRequest(), context.getResponse(), context);

        assertEquals("header WWW-Authenticate: Basic realm=\"a\\\"bX: y\"", sent.get(0));
    }
}
