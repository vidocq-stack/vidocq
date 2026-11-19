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
package io.vidocq.heisenberg.cdi.internal;

import io.vidocq.heisenberg.internal.PolicyComposer;
import jakarta.enterprise.context.control.RequestContextController;
import jakarta.enterprise.inject.Instance;

/**
 * Activates the CDI request context around the work of an {@code @Asynchronous} method, on its own
 * thread: MicroProfile Fault Tolerance requires it active there (§5). Through the standard
 * {@link RequestContextController}, so it works on any CDI container; a container without that
 * built-in bean (Vauban before Vidocq/vauban#147) leaves the work as it is. An already active
 * context is left alone, and only an activation made here is undone.
 */
final class RequestContextActivation implements PolicyComposer.AsyncContext {

    private final Instance<RequestContextController> controllers;

    RequestContextActivation(Instance<RequestContextController> controllers) {
        this.controllers = controllers;
    }

    @Override
    public PolicyComposer.Invocation wrap(PolicyComposer.Invocation invocation) {
        // No Instance when the interceptor is created outside a container (the unit tests do).
        if (controllers == null || !controllers.isResolvable()) {
            return invocation;
        }
        return () -> {
            RequestContextController controller = controllers.get();
            boolean activated = controller.activate();
            try {
                return invocation.proceed();
            } finally {
                if (activated) {
                    controller.deactivate();
                }
                controllers.destroy(controller);
            }
        };
    }
}
