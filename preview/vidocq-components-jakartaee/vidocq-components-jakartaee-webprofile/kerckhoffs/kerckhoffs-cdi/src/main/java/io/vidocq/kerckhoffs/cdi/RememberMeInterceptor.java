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
package io.vidocq.kerckhoffs.cdi;

import io.vidocq.kerckhoffs.core.internal.mechanism.RememberMeSupport;
import jakarta.annotation.Priority;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;
import jakarta.security.enterprise.AuthenticationStatus;
import jakarta.security.enterprise.authentication.mechanism.http.HttpMessageContext;
import jakarta.security.enterprise.authentication.mechanism.http.RememberMe;
import jakarta.security.enterprise.identitystore.RememberMeIdentityStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.Serializable;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Puts {@code @RememberMe} (Jakarta Security 4.0, section 3.8.2) around the {@code validateRequest} and
 * {@code cleanSubject} of the mechanism it is bound to. It runs just inside the automatic session
 * ({@link AutoApplySessionInterceptor}): a caller kept in the session is not asked for the cookie.
 *
 * <p>The expressions of the annotation see the mechanism as {@code self} and the context of the call as
 * {@code httpMessageContext}. The {@link RememberMeIdentityStore} is the one of the application; without it the
 * call fails, never silently runs without the remember-me.
 */
@RememberMe
@Interceptor
@Priority(Interceptor.Priority.PLATFORM_BEFORE + 210)
public class RememberMeInterceptor implements Serializable {

    private static final long serialVersionUID = 1L;

    private transient Supplier<RememberMeIdentityStore> store;

    /** For the container: the store is injected. */
    public RememberMeInterceptor() {
    }

    /** For the tests: the store is given. */
    RememberMeInterceptor(Supplier<RememberMeIdentityStore> store) {
        this.store = store;
    }

    @Inject
    void store(Instance<RememberMeIdentityStore> stores) {
        this.store = stores::get;
    }

    @AroundInvoke
    public Object intercept(InvocationContext invocation) throws Exception {
        Object[] parameters = invocation.getParameters();
        String method = invocation.getMethod().getName();
        if (parameters.length == 3 && parameters[0] instanceof HttpServletRequest request
                && parameters[2] instanceof HttpMessageContext context) {
            // The response may be null for a logout: HttpServletRequest.logout() has none to give (cleanSubject only).
            HttpServletResponse response = parameters[1] instanceof HttpServletResponse sent ? sent : null;
            if ("validateRequest".equals(method) && response != null) {
                return RememberMeSupport.validateRequest(request, response, context, store.get(),
                        () -> settings(invocation, context), () -> (AuthenticationStatus) invocation.proceed());
            }
            if ("cleanSubject".equals(method)) {
                try {
                    return invocation.proceed();
                } finally {
                    RememberMeSupport.cleanSubject(request, response, store.get(), settings(invocation, context));
                }
            }
        }
        // Any other call of the mechanism goes through untouched.
        return invocation.proceed();
    }

    private static RememberMeSupport.Settings settings(InvocationContext invocation, HttpMessageContext context) {
        RememberMe annotation = invocation.getInterceptorBinding(RememberMe.class);
        return RememberMeSupport.resolve(annotation,
                Map.of("self", invocation.getTarget(), "httpMessageContext", context));
    }
}
