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
import io.vidocq.kerckhoffs.core.internal.expression.ExpressionEvaluator;
import jakarta.security.enterprise.authentication.mechanism.http.HttpMessageContext;
import jakarta.security.enterprise.authentication.mechanism.http.RememberMe;
import jakarta.security.enterprise.credential.RememberMeCredential;
import jakarta.security.enterprise.identitystore.CredentialValidationResult;
import jakarta.security.enterprise.identitystore.RememberMeIdentityStore;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * {@code @RememberMe} (Jakarta Security 4.0, section 3.8.2): a cookie stands for a login the caller already made,
 * so the mechanism is not asked again while the token of the {@link RememberMeIdentityStore} is valid. The
 * interceptor of the CDI integration resolves the annotation into {@link Settings} and calls this class around
 * {@code validateRequest} and {@code cleanSubject} of the mechanism.
 *
 * <p>Fail closed: a token the store does not validate is never a login, and the client is told to drop its cookie.
 */
public final class RememberMeSupport {

    /**
     * What the annotation says, once its expressions are evaluated.
     *
     * @param cookieName the name of the cookie
     * @param maxAgeSeconds how long the client keeps it
     * @param secureOnly whether it travels over HTTPS only
     * @param httpOnly whether the page scripts cannot read it
     * @param rememberMe whether the caller who just logged in asked to be remembered, asked only after a login
     */
    public record Settings(String cookieName, int maxAgeSeconds, boolean secureOnly, boolean httpOnly,
                           BooleanSupplier rememberMe) {
    }

    /** The call of the mechanism the cookie spares. */
    @FunctionalInterface
    public interface Proceed<X extends Exception> {
        AuthenticationStatus validate() throws X;
    }

    private RememberMeSupport() {
    }

    /**
     * Reads the annotation: a non-empty {@code *Expression} attribute prevails over the literal attribute it
     * belongs to (section 3.8.2). The expressions see {@code variables}; {@code rememberMe} is evaluated only when
     * asked, after the login.
     *
     * @param annotation the {@code @RememberMe} of the mechanism
     * @param variables what the expressions may name, such as {@code self} and {@code httpMessageContext}
     * @return the settings of this call
     */
    public static Settings resolve(RememberMe annotation, Map<String, Object> variables) {
        return new Settings(annotation.cookieName(),
                number(annotation.cookieMaxAgeSecondsExpression(), annotation.cookieMaxAgeSeconds(), variables),
                flag(annotation.cookieSecureOnlyExpression(), annotation.cookieSecureOnly(), variables),
                flag(annotation.cookieHttpOnlyExpression(), annotation.cookieHttpOnly(), variables),
                () -> flag(annotation.isRememberMeExpression(), annotation.isRememberMe(), variables));
    }

    private static int number(String expression, int literal, Map<String, Object> variables) {
        return expression.isEmpty() ? literal
                : ExpressionEvaluator.evaluate(expression, Integer.class, variables);
    }

    private static boolean flag(String expression, boolean literal, Map<String, Object> variables) {
        return expression.isEmpty() ? literal
                : ExpressionEvaluator.evaluate(expression, Boolean.class, variables);
    }

    /**
     * Logs the caller in from the cookie, or asks the mechanism and remembers who it logged in.
     *
     * @param request the request
     * @param response the response, which carries the cookie
     * @param context the context of the call
     * @param store the store that validates and issues tokens
     * @param settings the settings of the annotation, evaluated for this call
     * @param proceed the call of the mechanism
     * @param <X> what the mechanism may throw
     * @return the status of the cookie login, or of the mechanism
     * @throws X when the mechanism throws
     */
    public static <X extends Exception> AuthenticationStatus validateRequest(HttpServletRequest request,
            HttpServletResponse response, HttpMessageContext context, RememberMeIdentityStore store,
            Supplier<Settings> settings, Proceed<X> proceed) throws X {
        Settings resolved = settings.get();
        Cookie cookie = cookie(request, resolved.cookieName());
        if (cookie != null) {
            CredentialValidationResult result = store.validate(new RememberMeCredential(cookie.getValue()));
            if (result.getStatus() == CredentialValidationResult.Status.VALID) {
                return context.notifyContainerAboutLogin(result);
            }
            expire(request, response, resolved);
        }
        AuthenticationStatus status = proceed.validate();
        if (status == AuthenticationStatus.SUCCESS && context.getCallerPrincipal() != null
                && resolved.rememberMe().getAsBoolean()) {
            String token = store.generateLoginToken(new CallerPrincipal(context.getCallerPrincipal().getName()),
                    context.getGroups());
            addCookie(request, response, resolved, token, resolved.maxAgeSeconds());
        }
        return status;
    }

    /**
     * Forgets the login the cookie stands for: the store drops the token and the client drops the cookie. The
     * token goes first and always: {@code HttpServletRequest.logout()} reaches the mechanism without a response, and
     * the cookie is then dead anyway, the next request finding no valid token behind it.
     *
     * @param request the request
     * @param response the response, or null when the container has none to offer
     * @param store the store that issued the token
     * @param settings the settings of the annotation
     */
    public static void cleanSubject(HttpServletRequest request, HttpServletResponse response,
                                    RememberMeIdentityStore store, Settings settings) {
        Cookie cookie = cookie(request, settings.cookieName());
        if (cookie == null) {
            return;
        }
        store.removeLoginToken(cookie.getValue());
        if (response != null) {
            expire(request, response, settings);
        }
    }

    private static Cookie cookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (name.equals(cookie.getName())) {
                    return cookie;
                }
            }
        }
        return null;
    }

    private static void expire(HttpServletRequest request, HttpServletResponse response, Settings settings) {
        addCookie(request, response, settings, "", 0);
    }

    /** Sends the cookie with the flags of the settings: they are set here, where the cookie is made and sent. */
    private static void addCookie(HttpServletRequest request, HttpServletResponse response, Settings settings,
                                  String value, int maxAge) {
        Cookie cookie = new Cookie(settings.cookieName(), value);
        cookie.setMaxAge(maxAge);
        cookie.setHttpOnly(settings.httpOnly());
        cookie.setSecure(settings.secureOnly());
        String path = request.getContextPath();
        cookie.setPath(path == null || path.isEmpty() ? "/" : path);
        response.addCookie(cookie);
    }
}
