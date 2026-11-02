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

import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/**
 * The fallback method of a guarded method is found and looked up once per bean class: every fallback used to scan the
 * class's methods and take a private lookup, twice (validation, then invocation).
 */
class FallbackHandleCacheTest {

    public static class Guarded {
        public String call(int n) {
            return "call " + n;
        }

        String recover(int n) {
            return "fallback " + n;
        }
    }

    @Test
    void theHandleOfAFallbackMethodIsResolvedOnce() throws Exception {
        Method call = Guarded.class.getMethod("call", int.class);

        var first = new FallbackResolver().fallbackHandle(Guarded.class, call, "recover");
        var second = new FallbackResolver().fallbackHandle(Guarded.class, call, "recover");

        assertSame(first, second);
    }
}
