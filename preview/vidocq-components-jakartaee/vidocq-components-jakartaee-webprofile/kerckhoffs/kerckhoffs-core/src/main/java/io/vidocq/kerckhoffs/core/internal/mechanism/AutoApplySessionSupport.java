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
import jakarta.security.enterprise.authentication.mechanism.http.HttpMessageContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import java.io.Serializable;
import java.util.Set;

/**
 * What {@code @AutoApplySession} does (Jakarta Security 4.0, section 3.8.6), apart from the CDI interceptor that
 * calls it: the caller a mechanism established is kept in the session; on a later request of that session it is
 * applied again and the mechanism is not asked. A request that carries a new authentication always reaches the
 * mechanism, and a login never keeps the session id it found.
 *
 * <p>Only the name and the groups are kept, so a caller restored from the session is a {@link CallerPrincipal}.
 */
public final class AutoApplySessionSupport {

    /** The session attribute that holds the caller this support stored; nothing else is trusted. */
    public static final String IDENTITY = "io.vidocq.kerckhoffs.session.caller";

    /** What the mechanism, wrapped by the interceptor, would do. */
    @FunctionalInterface
    public interface Proceed {
        AuthenticationStatus validate() throws Exception;
    }

    private record SessionCaller(String name, Set<String> groups) implements Serializable { }

    private AutoApplySessionSupport() {
    }

    /** Applies the caller of the session, or asks the mechanism and keeps the caller it identified. */
    public static AuthenticationStatus validateRequest(HttpServletRequest request, HttpMessageContext context,
                                                       Proceed proceed) throws Exception {
        if (!context.isAuthenticationRequest() && keptCaller(request) instanceof SessionCaller kept) {
            return context.notifyContainerAboutLogin(new CallerPrincipal(kept.name()), kept.groups());
        }
        AuthenticationStatus status = proceed.validate();
        if (status == AuthenticationStatus.SUCCESS && context.getCallerPrincipal() != null) {
            keep(request, context);
        }
        return status;
    }

    /** Forgets the caller kept in the session: the application logged out. */
    public static void cleanSubject(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) session.removeAttribute(IDENTITY);
    }

    private static SessionCaller keptCaller(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session != null && session.getAttribute(IDENTITY) instanceof SessionCaller kept ? kept : null;
    }

    private static void keep(HttpServletRequest request, HttpMessageContext context) {
        boolean existed = request.getSession(false) != null;
        HttpSession session = request.getSession(true);
        if (existed) request.changeSessionId();
        session.setAttribute(IDENTITY, new SessionCaller(context.getCallerPrincipal().getName(),
                Set.copyOf(context.getGroups())));
    }
}
