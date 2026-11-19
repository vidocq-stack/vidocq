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
import jakarta.security.auth.message.callback.CallerPrincipalCallback;
import jakarta.security.auth.message.callback.GroupPrincipalCallback;
import jakarta.security.auth.message.module.ServerAuthModule;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import javax.security.auth.Subject;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.UnsupportedCallbackException;

import java.security.Principal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The Servlet Container Profile of Jakarta Authentication 3.1 for one server authentication module: for each request it
 * builds the message, calls the module and reports what the module decided. It fails closed: a module that throws, a
 * status that has no meaning for a request or a callback it does not know is an {@link AuthException}, never a login.
 *
 * <p>It knows no servlet container: the container (Foy, an application server) calls it from its own request path.
 */
public final class ServletContainerProfile {

    /** The key of the message map that tells the module the resource is protected. */
    public static final String MANDATORY = "jakarta.security.auth.message.MessagePolicy.isMandatory";
    /** The key of the message map by which the module asks for the caller to be kept in the session. */
    public static final String REGISTER_SESSION = "jakarta.servlet.http.registerSession";
    /** The key of the message map by which the module gives the {@code getAuthType()} of the caller. */
    public static final String AUTH_TYPE = "jakarta.servlet.http.authType";

    /** What the module decided about a request. */
    public enum Status {
        /** The module named a caller: the request goes on as that caller. */
        AUTHENTICATED,
        /** The module let the request go on without a caller: a protected resource refuses it. */
        ANONYMOUS,
        /** The module produced the response (a redirect, a challenge, an error): the request stops. */
        RESPONSE_SENT
    }

    /**
     * The outcome of one request.
     *
     * @param status          what the module decided
     * @param caller          the caller, only when {@code status} is {@link Status#AUTHENTICATED}
     * @param groups          the groups of the caller; empty when there is none
     * @param registerSession whether the module asked for the caller to be kept in the session
     * @param authType        the value of {@code getAuthType()} the module gave, or {@code null}
     */
    public record Result(Status status, Principal caller, Set<String> groups, boolean registerSession,
                         String authType) {
        public Result {
            groups = groups == null ? Set.of() : Set.copyOf(groups);
        }
    }

    /** The caller a module named through a callback: only this profile's handler writes it into a subject. */
    private record Caller(Principal principal) implements Principal {
        @Override public String getName() { return principal.getName(); }
    }

    /** A caller a module named by its name only. */
    private record Named(String name) implements Principal {
        @Override public String getName() { return name; }
    }

    /** A group a module named through a callback. */
    private record Group(String name) implements Principal {
        @Override public String getName() { return name; }
    }

    private final ServerAuthModule module;

    private ServletContainerProfile(ServerAuthModule module) {
        this.module = module;
    }

    /** Initializes {@code module} with this profile's callback handler and drives it. */
    public static ServletContainerProfile of(ServerAuthModule module, Map<String, Object> options)
            throws AuthException {
        Objects.requireNonNull(module, "module");
        module.initialize(null, null, new Handler(), options == null ? Map.of() : options);
        return new ServletContainerProfile(module);
    }

    /**
     * Asks the module to authenticate one request.
     *
     * @param mandatory whether a constraint protects the resource
     * @throws AuthException when the module fails or answers a status that has no meaning for a request
     */
    public Result validateRequest(HttpServletRequest request, HttpServletResponse response, boolean mandatory)
            throws AuthException {
        var info = new Message(request, response);
        info.getMap().put(MANDATORY, Boolean.toString(mandatory));
        var client = new Subject();
        AuthStatus status = module.validateRequest(info, client, null);
        if (status == AuthStatus.SEND_FAILURE || status == AuthStatus.SEND_CONTINUE) {
            return new Result(Status.RESPONSE_SENT, null, Set.of(), false, null);
        }
        if (status != AuthStatus.SUCCESS) {
            throw new AuthException("a request cannot end with the status " + status);
        }
        boolean register = Boolean.parseBoolean(String.valueOf(info.getMap().get(REGISTER_SESSION)));
        String authType = info.getMap().get(AUTH_TYPE) instanceof String given ? given : null;
        Principal caller = null;
        Set<String> groups = new HashSet<>();
        for (Principal p : client.getPrincipals()) {
            if (p instanceof Caller(var principal)) caller = principal;
            else if (p instanceof Group(var name)) groups.add(name);
        }
        return caller == null ? new Result(Status.ANONYMOUS, null, Set.of(), register, null)
                : new Result(Status.AUTHENTICATED, caller, groups, register, authType);
    }

    /** Asks the module to forget the caller of {@code request}: the application logged out. */
    public void cleanSubject(HttpServletRequest request, HttpServletResponse response) throws AuthException {
        module.cleanSubject(new Message(request, response), new Subject());
    }

    /** The message of one request: its request, its response and a map for the policy and the session. */
    private static final class Message implements MessageInfo {
        private Object request;
        private Object response;
        private final Map<String, Object> map = new HashMap<>();

        Message(Object request, Object response) {
            this.request = request;
            this.response = response;
        }

        @Override public Object getRequestMessage() { return request; }
        @Override public Object getResponseMessage() { return response; }
        @Override public void setRequestMessage(Object request) { this.request = request; }
        @Override public void setResponseMessage(Object response) { this.response = response; }
        @Override public Map<String, Object> getMap() { return map; }
    }

    /** Writes the callers and groups a module names into the subject it names; knows nothing else. */
    private static final class Handler implements CallbackHandler {
        @Override
        public void handle(Callback[] callbacks) throws UnsupportedCallbackException {
            for (Callback callback : callbacks) {
                switch (callback) {
                    case CallerPrincipalCallback caller -> nameCaller(caller);
                    case GroupPrincipalCallback groups -> nameGroups(groups);
                    default -> throw new UnsupportedCallbackException(callback);
                }
            }
        }

        private static void nameCaller(CallerPrincipalCallback callback) {
            var principals = callback.getSubject().getPrincipals();
            principals.removeIf(Caller.class::isInstance);
            Principal named = callerOf(callback);
            if (named != null) principals.add(new Caller(named));
        }

        private static Principal callerOf(CallerPrincipalCallback callback) {
            if (callback.getPrincipal() != null) return callback.getPrincipal();
            if (callback.getName() == null) return null;
            return new Named(callback.getName());
        }

        private static void nameGroups(GroupPrincipalCallback callback) {
            var principals = callback.getSubject().getPrincipals();
            principals.removeIf(Group.class::isInstance);
            String[] names = callback.getGroups();
            if (names == null) return;
            for (String name : names) principals.add(new Group(name));
        }
    }
}
