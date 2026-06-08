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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Method;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.junit.jupiter.api.Test;

class FallbackPolicyTest {

    private final FallbackResolver resolver = new FallbackResolver();

    @Test
    void appliesFallbackWhenExceptionMatchesApplyOn() throws Exception {
        // MP FT 4.1 §6: fallback is triggered if the exception is in applyOn and not in skipOn.
        PolicyService service = new PolicyService();
        Method guardedMethod = PolicyService.class.getDeclaredMethod("guarded");
        Fallback fallback = guardedMethod.getAnnotation(Fallback.class);

        Object result = FallbackPolicy.execute(
                () -> {
                    throw new IllegalStateException("boom");
                },
                fallback,
                new FallbackConfig(fallback.applyOn(), fallback.skipOn(), fallback.fallbackMethod(), fallback.value()),
                service,
                guardedMethod,
                new Object[0],
                resolver
        );

        assertEquals("recovered", result);
    }

    @Test
    void doesNotApplyFallbackWhenExceptionMatchesSkipOn() throws Exception {
        PolicyService service = new PolicyService();
        Method guardedMethod = PolicyService.class.getDeclaredMethod("guarded");
        Fallback fallback = guardedMethod.getAnnotation(Fallback.class);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> FallbackPolicy.execute(
                        () -> {
                            throw new IllegalArgumentException("do not fallback");
                        },
                        fallback,
                        new FallbackConfig(fallback.applyOn(), fallback.skipOn(), fallback.fallbackMethod(), fallback.value()),
                        service,
                        guardedMethod,
                        new Object[0],
                        resolver
                )
        );

        assertEquals("do not fallback", error.getMessage());
    }

    static class PolicyService {
        @Fallback(
                fallbackMethod = "recover",
                applyOn = {IllegalStateException.class, RuntimeException.class},
                skipOn = {IllegalArgumentException.class}
        )
        String guarded() {
            return "unreachable";
        }

        String recover() {
            return "recovered";
        }
    }
}
