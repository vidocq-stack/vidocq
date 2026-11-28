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

import jakarta.security.auth.message.MessageInfo;
import jakarta.security.auth.message.callback.CallerPrincipalCallback;
import jakarta.security.auth.message.callback.GroupPrincipalCallback;
import jakarta.security.enterprise.AuthenticationStatus;
import jakarta.security.enterprise.CallerPrincipal;
import jakarta.security.enterprise.authentication.mechanism.http.AuthenticationParameters;
import jakarta.security.enterprise.authentication.mechanism.http.HttpMessageContext;
import jakarta.security.enterprise.identitystore.CredentialValidationResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import javax.security.auth.Subject;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;

import java.io.IOException;
import java.security.Principal;
import java.util.Objects;
import java.util.Set;

/**
 * The context an {@code HttpAuthenticationMechanism} acts through (Jakarta Security 4.0, section 3.8.3): it reports a
 * login to the container through the {@code CallbackHandler} of the Jakarta Authentication profile, and answers the
 * request when the mechanism decides to.
 *
 * <p>One instance serves one request. It fails closed: a caller without a name is a failure, never a login.
 */
public final class DefaultHttpMessageContext implements HttpMessageContext {

    /** The key of the {@code MessageInfo} map that says the resource is protected (Authentication 3.1, 3.8.4). */
    public static final String MANDATORY = "jakarta.security.auth.message.MessagePolicy.isMandatory";
    /** The key of the {@code MessageInfo} map that asks the container to keep the caller in the session. */
    public static final String REGISTER_SESSION = "jakarta.servlet.http.registerSession";
    /** The request attribute the application's {@code authenticate(...)} call leaves its parameters in. */
    public static final String PARAMETERS = "io.vidocq.kerckhoffs.authenticationParameters";

    private final MessageInfo info;
    private final Subject clientSubject;
    private final CallbackHandler handler;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private Principal callerPrincipal;
    private Set<String> groups = Set.of();

    public DefaultHttpMessageContext(MessageInfo info, Subject clientSubject, CallbackHandler handler) {
        this.info = Objects.requireNonNull(info, "info");
        this.clientSubject = Objects.requireNonNull(clientSubject, "clientSubject");
        this.handler = Objects.requireNonNull(handler, "handler");
        this.request = (HttpServletRequest) info.getRequestMessage();
        this.response = (HttpServletResponse) info.getResponseMessage();
    }

    @Override public boolean isProtected() { return Boolean.parseBoolean(String.valueOf(info.getMap().get(MANDATORY))); }

    @Override public boolean isAuthenticationRequest() { return request.getAttribute(PARAMETERS) != null; }

    @Override public boolean isRegisterSession() {
        return Boolean.parseBoolean(String.valueOf(info.getMap().get(REGISTER_SESSION)));
    }

    @Override public void setRegisterSession(String callerName, Set<String> callerGroups) {
        info.getMap().put(REGISTER_SESSION, Boolean.toString(true));
        notifyContainerAboutLogin(callerName, callerGroups);
    }

    @Override public void cleanClientSubject() {
        clientSubject.getPrincipals().clear();
        clientSubject.getPublicCredentials().clear();
        clientSubject.getPrivateCredentials().clear();
        callerPrincipal = null;
        groups = Set.of();
    }

    @Override public AuthenticationParameters getAuthParameters() {
        return request.getAttribute(PARAMETERS) instanceof AuthenticationParameters given
                ? given : new AuthenticationParameters();
    }

    @Override public CallbackHandler getHandler() { return handler; }

    @Override public MessageInfo getMessageInfo() { return info; }

    @Override public Subject getClientSubject() { return clientSubject; }

    @Override public HttpServletRequest getRequest() { return request; }

    @Override public void setRequest(HttpServletRequest request) { this.request = Objects.requireNonNull(request); }

    @Override public HttpMessageContext withRequest(HttpServletRequest other) {
        var copy = new DefaultHttpMessageContext(info, clientSubject, handler);
        copy.request = Objects.requireNonNull(other);
        copy.callerPrincipal = callerPrincipal;
        copy.groups = groups;
        return copy;
    }

    @Override public HttpServletResponse getResponse() { return response; }

    @Override public void setResponse(HttpServletResponse response) { this.response = Objects.requireNonNull(response); }

    @Override public AuthenticationStatus redirect(String location) {
        try {
            response.sendRedirect(response.encodeRedirectURL(location));
        } catch (IOException e) {
            throw new IllegalStateException("cannot redirect", e);
        }
        return AuthenticationStatus.SEND_CONTINUE;
    }

    @Override public AuthenticationStatus forward(String path) {
        try {
            request.getRequestDispatcher(path).forward(request, response);
        } catch (IOException | jakarta.servlet.ServletException e) {
            throw new IllegalStateException("cannot forward", e);
        }
        return AuthenticationStatus.SEND_CONTINUE;
    }

    @Override public AuthenticationStatus responseUnauthorized() {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        return AuthenticationStatus.SEND_FAILURE;
    }

    @Override public AuthenticationStatus responseNotFound() {
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        return AuthenticationStatus.SEND_FAILURE;
    }

    @Override public AuthenticationStatus notifyContainerAboutLogin(String callerName, Set<String> callerGroups) {
        if (callerName == null || callerName.isBlank()) return AuthenticationStatus.SEND_FAILURE;
        return notifyContainerAboutLogin(new CallerPrincipal(callerName), callerGroups);
    }

    @Override public AuthenticationStatus notifyContainerAboutLogin(Principal principal, Set<String> callerGroups) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            return AuthenticationStatus.SEND_FAILURE;
        }
        Set<String> granted = callerGroups == null ? Set.of() : Set.copyOf(callerGroups);
        try {
            handler.handle(new Callback[] {
                new CallerPrincipalCallback(clientSubject, principal),
                new GroupPrincipalCallback(clientSubject, granted.toArray(new String[0]))});
        } catch (IOException | javax.security.auth.callback.UnsupportedCallbackException e) {
            throw new IllegalStateException("the container refused the login", e);
        }
        callerPrincipal = principal;
        groups = granted;
        return AuthenticationStatus.SUCCESS;
    }

    @Override public AuthenticationStatus notifyContainerAboutLogin(CredentialValidationResult result) {
        return switch (result.getStatus()) {
            case VALID -> notifyContainerAboutLogin(result.getCallerPrincipal(), result.getCallerGroups());
            case INVALID -> AuthenticationStatus.SEND_FAILURE;
            case NOT_VALIDATED -> AuthenticationStatus.NOT_DONE;
        };
    }

    @Override public AuthenticationStatus doNothing() { return AuthenticationStatus.NOT_DONE; }

    @Override public Principal getCallerPrincipal() { return callerPrincipal; }

    @Override public Set<String> getGroups() { return groups; }
}
