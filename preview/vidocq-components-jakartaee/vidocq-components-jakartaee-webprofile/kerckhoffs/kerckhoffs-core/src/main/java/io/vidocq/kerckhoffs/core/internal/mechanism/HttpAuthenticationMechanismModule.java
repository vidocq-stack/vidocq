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

import jakarta.security.auth.message.AuthException;
import jakarta.security.auth.message.AuthStatus;
import jakarta.security.auth.message.MessageInfo;
import jakarta.security.auth.message.MessagePolicy;
import jakarta.security.auth.message.module.ServerAuthModule;
import jakarta.security.enterprise.AuthenticationException;
import jakarta.security.enterprise.AuthenticationStatus;
import jakarta.security.enterprise.authentication.mechanism.http.HttpAuthenticationMechanism;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import javax.security.auth.Subject;
import javax.security.auth.callback.CallbackHandler;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The server authentication module that places an {@link HttpAuthenticationMechanism} in service through Jakarta
 * Authentication (Jakarta Security 4.0, section 2.5). It fails closed: a mechanism that throws or answers nothing is a
 * failed request, and a module nobody initialized refuses every request.
 */
public final class HttpAuthenticationMechanismModule implements ServerAuthModule {

    private static final Class<?>[] MESSAGE_TYPES = {HttpServletRequest.class, HttpServletResponse.class};

    private final HttpAuthenticationMechanism mechanism;
    private final AtomicReference<CallbackHandler> handler = new AtomicReference<>();

    public HttpAuthenticationMechanismModule(HttpAuthenticationMechanism mechanism) {
        this.mechanism = Objects.requireNonNull(mechanism, "mechanism");
    }

    @Override
    public void initialize(MessagePolicy requestPolicy, MessagePolicy responsePolicy, CallbackHandler handler,
                           Map<String, Object> options) throws AuthException {
        if (handler == null) throw new AuthException("a callback handler is required");
        this.handler.set(handler);
    }

    @Override public Class<?>[] getSupportedMessageTypes() { return MESSAGE_TYPES.clone(); }

    @Override
    public AuthStatus validateRequest(MessageInfo info, Subject clientSubject, Subject serviceSubject)
            throws AuthException {
        var context = context(info, clientSubject);
        AuthenticationStatus status;
        try {
            status = mechanism.validateRequest(context.getRequest(), context.getResponse(), context);
        } catch (AuthenticationException e) {
            throw authException("the authentication mechanism failed", e);
        }
        if (status == null) throw new AuthException("the authentication mechanism answered nothing");
        return switch (status) {
            case SUCCESS, NOT_DONE -> AuthStatus.SUCCESS;
            case SEND_CONTINUE -> AuthStatus.SEND_CONTINUE;
            case SEND_FAILURE -> AuthStatus.SEND_FAILURE;
        };
    }

    @Override
    public AuthStatus secureResponse(MessageInfo info, Subject serviceSubject) throws AuthException {
        var context = context(info, new Subject());
        AuthenticationStatus status;
        try {
            status = mechanism.secureResponse(context.getRequest(), context.getResponse(), context);
        } catch (AuthenticationException e) {
            throw authException("the authentication mechanism failed to secure the response", e);
        }
        if (status == null) throw new AuthException("the authentication mechanism answered nothing");
        return switch (status) {
            case SUCCESS, NOT_DONE -> AuthStatus.SEND_SUCCESS;
            case SEND_CONTINUE -> AuthStatus.SEND_CONTINUE;
            case SEND_FAILURE -> AuthStatus.SEND_FAILURE;
        };
    }

    @Override
    public void cleanSubject(MessageInfo info, Subject subject) {
        var context = context(info, subject);
        mechanism.cleanSubject(context.getRequest(), context.getResponse(), context);
        context.cleanClientSubject();
    }

    private DefaultHttpMessageContext context(MessageInfo info, Subject clientSubject) {
        CallbackHandler given = handler.get();
        if (given == null) throw new IllegalStateException("the module was not initialized");
        return new DefaultHttpMessageContext(info, clientSubject == null ? new Subject() : clientSubject, given);
    }

    private static AuthException authException(String message, Exception cause) {
        var failure = new AuthException(message);
        failure.initCause(cause);
        return failure;
    }
}
