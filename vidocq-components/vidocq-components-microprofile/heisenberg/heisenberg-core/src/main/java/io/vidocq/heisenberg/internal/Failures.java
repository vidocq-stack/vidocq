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
package io.vidocq.heisenberg.internal;

import java.lang.reflect.UndeclaredThrowableException;

/**
 * Hands a failure of the guarded method back to a chain that declares {@code throws Exception}, as
 * {@code jakarta.interceptor.InvocationContext#proceed} does: exceptions travel as they are, errors are
 * thrown as they are, and anything else is wrapped in an {@link UndeclaredThrowableException}.
 */
final class Failures {

    private Failures() {}

    /**
     * The exception to throw for {@code failure}.
     *
     * @throws Error {@code failure} itself, when it is an {@link Error}
     */
    static Exception asException(Throwable failure) {
        if (failure instanceof Exception exception) {
            return exception;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        return new UndeclaredThrowableException(failure);
    }
}
