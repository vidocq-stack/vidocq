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
package io.vidocq.runtime.extensions.jakartaee.web.kerckhoffs;

import io.vidocq.kerckhoffs.cdi.AutoApplySessionInterceptor;
import jakarta.annotation.Priority;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;
import jakarta.security.enterprise.authentication.mechanism.http.AutoApplySession;

import java.io.Serializable;

/**
 * The interceptor of {@link AutoApplySession} (Jakarta Security 4.0, section 3.8.6), as a bean of this module: Vauban
 * weaves it ahead of time, which it cannot do for {@link AutoApplySessionInterceptor}, a class of a module it does not
 * process. The work is Kerckhoffs's.
 */
@AutoApplySession
@Interceptor
@Priority(Interceptor.Priority.PLATFORM_BEFORE + 200)
public class VidocqAutoApplySessionInterceptor implements Serializable {

    private static final long serialVersionUID = 1L;

    private final AutoApplySessionInterceptor interceptor = new AutoApplySessionInterceptor();

    @AroundInvoke
    public Object intercept(InvocationContext invocation) throws Exception {
        return interceptor.intercept(invocation);
    }
}
