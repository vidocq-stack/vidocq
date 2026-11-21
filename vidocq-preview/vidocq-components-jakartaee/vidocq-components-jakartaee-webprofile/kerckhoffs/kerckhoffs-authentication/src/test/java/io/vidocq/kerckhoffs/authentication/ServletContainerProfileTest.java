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
package io.vidocq.kerckhoffs.authentication;

import jakarta.security.auth.message.AuthException;
import jakarta.security.auth.message.AuthStatus;
import jakarta.security.auth.message.MessageInfo;
import jakarta.security.auth.message.MessagePolicy;
import jakarta.security.auth.message.callback.CallerPrincipalCallback;
import jakarta.security.auth.message.callback.GroupPrincipalCallback;
import jakarta.security.auth.message.callback.PasswordValidationCallback;
import jakarta.security.auth.message.module.ServerAuthModule;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import javax.security.auth.Subject;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.UnsupportedCallbackException;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Servlet Container Profile of Jakarta Authentication 3.1 for one request: it drives a server authentication module
 * and reports what the module decided. A request is never let in on the strength of a failure; the refusals come first.
 */
class ServletContainerProfileTest {

    /** What the module does with the profile's handler and the message; it answers what the script returns. */
    private interface Script {
        AuthStatus run(CallbackHandler handler, MessageInfo info, Subject client) throws Exception;
    }

    private static final class ScriptedModule implements ServerAuthModule {
        private final Script script;
        private CallbackHandler handler;
        final List<MessageInfo> cleaned = new ArrayList<>();

        ScriptedModule(Script script) { this.script = script; }

        @Override public void initialize(MessagePolicy request, MessagePolicy response, CallbackHandler handler,
                                         Map<String, Object> options) {
            this.handler = handler;
        }

        @Override public Class<?>[] getSupportedMessageTypes() {
            return new Class<?>[] {HttpServletRequest.class, HttpServletResponse.class};
        }

        @Override public AuthStatus validateRequest(MessageInfo info, Subject client, Subject service)
                throws AuthException {
            try {
                return script.run(handler, info, client);
            } catch (AuthException e) {
                throw e;
            } catch (Exception e) {
                throw new AuthException(e.getMessage());
            }
        }

        @Override public AuthStatus secureResponse(MessageInfo info, Subject service) { return AuthStatus.SEND_SUCCESS; }

        @Override public void cleanSubject(MessageInfo info, Subject subject) { cleaned.add(info); }
    }

    private static final HttpServletRequest REQUEST = proxy(HttpServletRequest.class);
    private static final HttpServletResponse RESPONSE = proxy(HttpServletResponse.class);

    private static <T> T proxy(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(ServletContainerProfileTest.class.getClassLoader(),
                new Class<?>[] {type}, (p, method, args) -> null));
    }

    private static ServletContainerProfile profile(Script script) throws AuthException {
        return ServletContainerProfile.of(new ScriptedModule(script), Map.of());
    }

    private static void login(CallbackHandler handler, Subject client, String name, String... groups)
            throws IOException, UnsupportedCallbackException {
        handler.handle(new Callback[] {new CallerPrincipalCallback(client, name), new GroupPrincipalCallback(client, groups)});
    }

    @Test
    void aModuleThatFailsLetsNobodyIn() throws Exception {
        var profile = profile((handler, info, client) -> {
            throw new AuthException("boom");
        });

        assertThrows(AuthException.class, () -> profile.validateRequest(REQUEST, RESPONSE, true));
    }

    @Test
    void aFailureStatusStopsTheRequestWithoutACaller() throws Exception {
        var profile = profile((handler, info, client) -> {
            login(handler, client, "reza");
            return AuthStatus.SEND_FAILURE;
        });

        var result = profile.validateRequest(REQUEST, RESPONSE, true);

        assertEquals(ServletContainerProfile.Status.RESPONSE_SENT, result.status());
        assertNull(result.caller(), "a caller named by a failed module is not a caller");
    }

    @Test
    void aContinueStatusStopsTheRequestToo() throws Exception {
        var result = profile((handler, info, client) -> AuthStatus.SEND_CONTINUE)
                .validateRequest(REQUEST, RESPONSE, false);

        assertEquals(ServletContainerProfile.Status.RESPONSE_SENT, result.status());
    }

    @Test
    void aSuccessWithoutCallerIsAnonymous() throws Exception {
        var result = profile((handler, info, client) -> AuthStatus.SUCCESS).validateRequest(REQUEST, RESPONSE, false);

        assertEquals(ServletContainerProfile.Status.ANONYMOUS, result.status());
        assertNull(result.caller());
    }

    @Test
    void aStatusOfNoMeaningToARequestIsRefused() throws Exception {
        var profile = profile((handler, info, client) -> AuthStatus.SEND_SUCCESS);

        assertThrows(AuthException.class, () -> profile.validateRequest(REQUEST, RESPONSE, false));
    }

    @Test
    void aCallbackTheProfileDoesNotKnowIsRefused() throws Exception {
        var profile = profile((handler, info, client) -> {
            handler.handle(new Callback[] {new PasswordValidationCallback(client, "reza", "secret1".toCharArray())});
            return AuthStatus.SUCCESS;
        });

        assertThrows(AuthException.class, () -> profile.validateRequest(REQUEST, RESPONSE, true));
    }

    @Test
    void aCallerOfAnotherSubjectIsIgnored() throws Exception {
        var result = profile((handler, info, client) -> {
            login(handler, new Subject(), "mallory", "admin");
            return AuthStatus.SUCCESS;
        }).validateRequest(REQUEST, RESPONSE, true);

        assertEquals(ServletContainerProfile.Status.ANONYMOUS, result.status());
    }

    @Test
    void aCallerAndItsGroupsAreReportedWhateverTheOrderOfTheCallbacks() throws Exception {
        var result = profile((handler, info, client) -> {
            handler.handle(new Callback[] {new GroupPrincipalCallback(client, new String[] {"foo", "bar"})});
            handler.handle(new Callback[] {new CallerPrincipalCallback(client, "reza")});
            return AuthStatus.SUCCESS;
        }).validateRequest(REQUEST, RESPONSE, true);

        assertEquals(ServletContainerProfile.Status.AUTHENTICATED, result.status());
        assertEquals("reza", result.caller().getName());
        assertEquals(Set.of("foo", "bar"), result.groups());
    }

    @Test
    void theModuleIsToldWhetherTheRequestIsMandatory() throws Exception {
        var seen = new ArrayList<Object>();
        var profile = profile((handler, info, client) -> {
            seen.add(info.getMap().get("jakarta.security.auth.message.MessagePolicy.isMandatory"));
            assertTrue(info.getRequestMessage() instanceof HttpServletRequest);
            return AuthStatus.SUCCESS;
        });

        profile.validateRequest(REQUEST, RESPONSE, true);
        profile.validateRequest(REQUEST, RESPONSE, false);

        assertEquals(List.of("true", "false"), seen);
    }

    @Test
    void aSessionIsRegisteredOnlyWhenTheModuleAsksForIt() throws Exception {
        var plain = profile((handler, info, client) -> {
            login(handler, client, "reza");
            return AuthStatus.SUCCESS;
        }).validateRequest(REQUEST, RESPONSE, true);
        var registering = profile((handler, info, client) -> {
            info.getMap().put("jakarta.servlet.http.registerSession", "true");
            login(handler, client, "reza");
            return AuthStatus.SUCCESS;
        }).validateRequest(REQUEST, RESPONSE, true);

        assertFalse(plain.registerSession());
        assertTrue(registering.registerSession());
    }

    @Test
    void theModuleCleansTheSubjectOfALogout() throws Exception {
        var module = new ScriptedModule((handler, info, client) -> AuthStatus.SUCCESS);
        var profile = ServletContainerProfile.of(module, Map.of());

        profile.cleanSubject(REQUEST, RESPONSE);

        assertEquals(1, module.cleaned.size());
    }

    @Test
    void theAuthTypeIsTheOneTheModuleSaidOrNone() throws Exception {
        var plain = profile((handler, info, client) -> {
            login(handler, client, "reza");
            return AuthStatus.SUCCESS;
        }).validateRequest(REQUEST, RESPONSE, true);
        var typed = profile((handler, info, client) -> {
            info.getMap().put("jakarta.servlet.http.authType", "BASIC");
            login(handler, client, "reza");
            return AuthStatus.SUCCESS;
        }).validateRequest(REQUEST, RESPONSE, true);

        assertNull(plain.authType());
        assertEquals("BASIC", typed.authType());
    }
}
