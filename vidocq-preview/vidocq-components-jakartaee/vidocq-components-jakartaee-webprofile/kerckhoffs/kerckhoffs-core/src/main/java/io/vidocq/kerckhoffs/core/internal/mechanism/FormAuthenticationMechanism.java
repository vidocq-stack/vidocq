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
import jakarta.security.enterprise.authentication.mechanism.http.HttpAuthenticationMechanism;
import jakarta.security.enterprise.authentication.mechanism.http.HttpMessageContext;
import jakarta.security.enterprise.credential.Credential;
import jakarta.security.enterprise.credential.Password;
import jakarta.security.enterprise.credential.UsernamePasswordCredential;
import jakarta.security.enterprise.identitystore.CredentialValidationResult;
import jakarta.security.enterprise.identitystore.IdentityStoreHandler;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.Serializable;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The built-in FORM mechanism of {@code @FormAuthenticationMechanismDefinition} with its login to continue (Jakarta
 * Security 4.0, sections 3.8.1.2 and 3.8.5). A protected resource of nobody leads to the login page; the form is posted
 * to {@code j_security_check}; the caller is remembered in the session and the browser goes back to the page it asked
 * for. It fails closed: credentials are read from a POST only, a failed login remembers nothing, and a login renews the
 * session id. {@link #customForm} gives the custom FORM mechanism, whose login page is the application's.
 */
public final class FormAuthenticationMechanism implements HttpAuthenticationMechanism {

    /** The session attribute that holds the caller a login remembered. */
    public static final String IDENTITY = "io.vidocq.kerckhoffs.form.identity";
    /** The session attribute that holds the page a login must go back to. */
    static final String REQUESTED = "io.vidocq.kerckhoffs.form.requested";
    private static final String ACTION = "/j_security_check";

    /** The caller a login remembered; what the session holds, so it is serializable. */
    private record Remembered(String caller, Set<String> groups) implements Serializable {
        private Remembered {
            groups = Set.copyOf(groups);
        }
    }

    private final String loginPage;
    private final String errorPage;
    private final boolean forwardToLogin;
    private final Supplier<IdentityStoreHandler> handler;
    /** Whether the login page is the application's, which hands the credential to {@code SecurityContext}. */
    private final boolean custom;

    public FormAuthenticationMechanism(String loginPage, String errorPage, boolean forwardToLogin,
                                       Supplier<IdentityStoreHandler> handler) {
        this(loginPage, errorPage, forwardToLogin, handler, false);
    }

    private FormAuthenticationMechanism(String loginPage, String errorPage, boolean forwardToLogin,
                                        Supplier<IdentityStoreHandler> handler, boolean custom) {
        this.loginPage = Objects.requireNonNull(loginPage, "loginPage");
        this.errorPage = errorPage == null ? "" : errorPage;
        this.forwardToLogin = forwardToLogin;
        this.handler = Objects.requireNonNull(handler, "handler");
        this.custom = custom;
    }

    /**
     * The built-in custom FORM mechanism of {@code @CustomFormAuthenticationMechanismDefinition} (section 3.8.1): the
     * same login to continue, but the login page belongs to the application, which validates nothing itself and hands
     * the credential to {@code SecurityContext.authenticate}; {@code j_security_check} is no login. A credential that
     * is not valid fails the call ({@code SEND_FAILURE}) and the application's page tells the caller: there is no error
     * page. A valid one logs the caller in on that same request ({@code SUCCESS}), or sends the browser back to the
     * page it asked for when a protected page led to the login ({@code SEND_CONTINUE}).
     */
    public static FormAuthenticationMechanism customForm(String loginPage, boolean forwardToLogin,
                                                         Supplier<IdentityStoreHandler> handler) {
        return new FormAuthenticationMechanism(loginPage, "", forwardToLogin, handler, true);
    }

    @Override
    public AuthenticationStatus validateRequest(HttpServletRequest request, HttpServletResponse response,
                                                HttpMessageContext context) {
        if (custom) {
            Credential given = context.isAuthenticationRequest() ? context.getAuthParameters().getCredential() : null;
            if (given != null) return callerLogin(request, context, given);
        } else if (isLogin(request)) {
            return login(request, context);
        }
        if (remembered(request) instanceof Remembered(var caller, var groups)) {
            return context.notifyContainerAboutLogin(caller, groups);
        }
        if (!context.isProtected()) return context.doNothing();
        if ("GET".equals(request.getMethod())) request.getSession().setAttribute(REQUESTED, requested(request));
        return go(request, context, loginPage);
    }

    @Override
    public void cleanSubject(HttpServletRequest request, HttpServletResponse response, HttpMessageContext context) {
        HttpSession session = request.getSession(false);
        if (session == null) return;
        session.removeAttribute(IDENTITY);
        session.removeAttribute(REQUESTED);
    }

    private static boolean isLogin(HttpServletRequest request) {
        return "POST".equals(request.getMethod()) && (request.getContextPath() + ACTION).equals(request.getRequestURI());
    }

    private AuthenticationStatus login(HttpServletRequest request, HttpMessageContext context) {
        String user = request.getParameter("j_username");
        String password = request.getParameter("j_password");
        if (user == null || user.isEmpty() || password == null) return failed(request, context);
        var credential = new UsernamePasswordCredential(user, new Password(password.toCharArray()));
        try {
            CredentialValidationResult result = handler.get().validate(credential);
            if (result.getStatus() != CredentialValidationResult.Status.VALID) return failed(request, context);
            String back = remember(request, result);
            return context.redirect(back == null ? request.getContextPath() + "/" : back);
        } finally {
            credential.clear();
        }
    }

    /** The login of a custom form: the credential the application handed to {@code SecurityContext.authenticate}. */
    private AuthenticationStatus callerLogin(HttpServletRequest request, HttpMessageContext context,
                                             Credential credential) {
        try {
            CredentialValidationResult result = handler.get().validate(credential);
            // Fail closed: a credential no store validated is no login either.
            if (result.getStatus() != CredentialValidationResult.Status.VALID) return AuthenticationStatus.SEND_FAILURE;
            String back = remember(request, result);
            return back == null ? context.notifyContainerAboutLogin(result) : context.redirect(back);
        } finally {
            credential.clear();
        }
    }

    /**
     * Remembers the caller of a valid login in the session, under a new session id, and answers the page the login
     * must go back to, or {@code null} when no protected page led to it.
     */
    private static String remember(HttpServletRequest request, CredentialValidationResult result) {
        HttpSession session = request.getSession();
        request.changeSessionId();
        String back = session.getAttribute(REQUESTED) instanceof String page ? page : null;
        session.removeAttribute(REQUESTED);
        session.setAttribute(IDENTITY, new Remembered(result.getCallerPrincipal().getName(), result.getCallerGroups()));
        return back;
    }

    @Override
    public String toString() {
        return (custom ? "custom FORM" : "FORM") + " mechanism, login page " + loginPage;
    }

    private AuthenticationStatus failed(HttpServletRequest request, HttpMessageContext context) {
        return errorPage.isEmpty() ? context.responseUnauthorized() : go(request, context, errorPage);
    }

    private AuthenticationStatus go(HttpServletRequest request, HttpMessageContext context, String page) {
        return forwardToLogin ? context.withRequest(new AsGet(request)).forward(page)
                : context.redirect(request.getContextPath() + page);
    }

    /** The request a page is forwarded: the login and error pages are read with GET, whatever was posted to get there. */
    private static final class AsGet extends HttpServletRequestWrapper {
        AsGet(HttpServletRequest request) {
            super(request);
        }

        @Override
        public String getMethod() {
            return "GET";
        }
    }

    private static Remembered remembered(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session != null && session.getAttribute(IDENTITY) instanceof Remembered found ? found : null;
    }

    /** The page the browser asked for, as a path of this server: the only thing a login ever redirects to. */
    private static String requested(HttpServletRequest request) {
        String query = request.getQueryString();
        return query == null ? request.getRequestURI() : request.getRequestURI() + "?" + query;
    }
}
