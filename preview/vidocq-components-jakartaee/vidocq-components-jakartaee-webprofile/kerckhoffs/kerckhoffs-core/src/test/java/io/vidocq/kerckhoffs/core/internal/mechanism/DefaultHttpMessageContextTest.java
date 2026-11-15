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

import jakarta.security.auth.message.callback.CallerPrincipalCallback;
import jakarta.security.auth.message.callback.GroupPrincipalCallback;
import jakarta.security.enterprise.AuthenticationStatus;
import jakarta.security.enterprise.identitystore.CredentialValidationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.security.auth.Subject;
import javax.security.auth.callback.Callback;

import java.security.Principal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The context an {@code HttpAuthenticationMechanism} acts through (Jakarta Security 4.0, section 3.8.3). The refusals
 * come first: a context that cannot tell a failure from a success would let anybody in.
 */
class DefaultHttpMessageContextTest {

    private final Map<String, Object> attributes = new HashMap<>();
    private final List<String> sent = new ArrayList<>();
    private final List<String> forwarded = new ArrayList<>();
    private final List<Callback> callbacks = new ArrayList<>();
    private final Subject subject = new Subject();
    private final Map<String, Object> policy = new HashMap<>();
    private DefaultHttpMessageContext context;

    @BeforeEach
    void context() {
        var info = new Fakes.Info(Fakes.request(attributes, forwarded), Fakes.response(sent), policy);
        context = new DefaultHttpMessageContext(info, subject, cbs -> callbacks.addAll(Arrays.asList(cbs)));
    }

    private static CredentialValidationResult valid(String caller, String... groups) {
        return new CredentialValidationResult(caller, Set.of(groups));
    }

    @Test
    void aFailedValidationLogsNobodyIn() {
        assertEquals(AuthenticationStatus.SEND_FAILURE, context.notifyContainerAboutLogin(CredentialValidationResult.INVALID_RESULT));

        assertTrue(callbacks.isEmpty(), "the container is told nothing about a caller that is not one");
        assertNull(context.getCallerPrincipal());
    }

    @Test
    void anUnvalidatedCredentialIsNotDoneEither() {
        assertEquals(AuthenticationStatus.NOT_DONE, context.notifyContainerAboutLogin(CredentialValidationResult.NOT_VALIDATED_RESULT));

        assertTrue(callbacks.isEmpty());
    }

    @Test
    void aMissingCallerNameIsRefusedNotLoggedIn() {
        assertEquals(AuthenticationStatus.SEND_FAILURE, context.notifyContainerAboutLogin((String) null, Set.of("foo")));
        assertEquals(AuthenticationStatus.SEND_FAILURE, context.notifyContainerAboutLogin((Principal) null, Set.of("foo")));

        assertTrue(callbacks.isEmpty());
    }

    @Test
    void aValidCallerAndItsGroupsAreHandedToTheContainer() {
        assertEquals(AuthenticationStatus.SUCCESS, context.notifyContainerAboutLogin(valid("reza", "foo", "bar")));

        var caller = (CallerPrincipalCallback) callbacks.get(0);
        var groups = (GroupPrincipalCallback) callbacks.get(1);
        assertEquals("reza", caller.getPrincipal().getName());
        assertEquals(Set.of("foo", "bar"), Set.of(groups.getGroups()));
        assertEquals(subject, caller.getSubject());
        assertEquals("reza", context.getCallerPrincipal().getName());
        assertEquals(Set.of("foo", "bar"), context.getGroups());
    }

    @Test
    void aCallerWithoutGroupsIsLoggedInWithAnEmptySet() {
        assertEquals(AuthenticationStatus.SUCCESS, context.notifyContainerAboutLogin("reza", null));

        assertEquals(Set.of(), context.getGroups());
    }

    @Test
    void theResponseCodesAreFailuresAndTheFlowControlIsContinue() {
        assertEquals(AuthenticationStatus.SEND_FAILURE, context.responseUnauthorized());
        assertEquals(AuthenticationStatus.SEND_FAILURE, context.responseNotFound());
        assertEquals(AuthenticationStatus.NOT_DONE, context.doNothing());
        assertEquals(List.of("status 401", "status 404"), sent);
    }

    @Test
    void aRedirectAndAForwardSendTheClientOn() {
        assertEquals(AuthenticationStatus.SEND_CONTINUE, context.redirect("/app/login"));
        assertEquals(AuthenticationStatus.SEND_CONTINUE, context.forward("/login.xhtml"));

        assertEquals(List.of("redirect /app/login"), sent);
        assertEquals(List.of("/login.xhtml"), forwarded);
    }

    @Test
    void theRequestIsProtectedOnlyWhenTheContainerSaysSo() {
        assertFalse(context.isProtected());

        policy.put(DefaultHttpMessageContext.MANDATORY, "true");

        assertTrue(context.isProtected());
    }

    @Test
    void theSessionIsRegisteredOnlyWhenAskedAndOnlyOnce() {
        assertFalse(context.isRegisterSession());

        context.setRegisterSession("reza", Set.of("foo"));

        assertTrue(context.isRegisterSession());
        assertEquals("true", policy.get(DefaultHttpMessageContext.REGISTER_SESSION));
    }

    @Test
    void anAuthenticationRequestIsOneWhoseParametersTheApplicationGave() {
        assertFalse(context.isAuthenticationRequest());
        assertNull(context.getAuthParameters().getCredential());

        attributes.put(DefaultHttpMessageContext.PARAMETERS,
                new jakarta.security.enterprise.authentication.mechanism.http.AuthenticationParameters().newAuthentication(true));

        assertTrue(context.isAuthenticationRequest());
        assertTrue(context.getAuthParameters().isNewAuthentication());
    }
}
