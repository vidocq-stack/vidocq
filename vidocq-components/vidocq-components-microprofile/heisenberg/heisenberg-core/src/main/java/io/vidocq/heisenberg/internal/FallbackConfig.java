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

import org.eclipse.microprofile.faulttolerance.FallbackHandler;

/**
 * Immutable configuration for the {@code @Fallback} policy.
 *
 * <p>MP FT 4.1 §6 and §9: only {@code applyOn} and {@code skipOn} can be overridden
 * through MicroProfile Config. The {@code value} (handler class) and {@code fallbackMethod}
 * are fixed at compile time to allow their validation at container startup.</p>
 *
 * @param applyOn types of exception that trigger the fallback (spec default: {@link Throwable})
 * @param skipOn  types of exception that bypass the fallback and are propagated as-is
 * @param fallbackMethod name of the effective fallbackMethod (annotation or config override)
 * @param fallbackHandlerClass type of the effective FallbackHandler (annotation or config override)
 */
public record FallbackConfig(
        Class<? extends Throwable>[] applyOn,
        Class<? extends Throwable>[] skipOn,
        String fallbackMethod,
        Class<? extends FallbackHandler<?>> fallbackHandlerClass
) {

    public FallbackConfig {
        if (applyOn == null) {
            throw new IllegalArgumentException("applyOn must not be null");
        }
        if (skipOn == null) {
            throw new IllegalArgumentException("skipOn must not be null");
        }
    }

    /**
     * Indicates whether {@code failure} should trigger the fallback according to the {@code applyOn}/{@code skipOn} rules.
     */
    public boolean shouldApplyFallback(Throwable failure) {
        for (Class<? extends Throwable> declaredType : skipOn) {
            if (declaredType.isAssignableFrom(failure.getClass())) {
                return false;
            }
        }
        for (Class<? extends Throwable> declaredType : applyOn) {
            if (declaredType.isAssignableFrom(failure.getClass())) {
                return true;
            }
        }
        return false;
    }
}

