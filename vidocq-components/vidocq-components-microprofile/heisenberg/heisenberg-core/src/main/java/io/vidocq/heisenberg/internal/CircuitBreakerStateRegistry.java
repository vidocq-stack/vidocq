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

/**
 * Circuit Breaker state registry — interface for isolating state management.
 *
 * <p>Implementation produced in heisenberg-cdi-vauban {@link io.vidocq.heisenberg.cdi.internal.StateRegistry}.</p>
 */
public interface CircuitBreakerStateRegistry {

    /**
     * Returns the current circuit-breaker state for a given method.
     */
    CircuitBreakerState getState(String beanClass, String methodName);

    /**
     * Records a success in HALF_OPEN state.
     */
    void recordSuccess(String beanClass, String methodName);

    /**
     * Records a failure (increments the error counter).
     */
    void recordFailure(String beanClass, String methodName);

    /**
     * Forces the circuit into OPEN state (manual transition for tests).
     */
    void setOpen(String beanClass, String methodName);

    /**
     * Forces the circuit into CLOSED state (reset).
     */
    void setClosed(String beanClass, String methodName);

    /**
     * Forces the circuit into HALF_OPEN state.
     */
    void setHalfOpen(String beanClass, String methodName);

    /**
     * Returns the number of milliseconds elapsed since the circuit opened.
     */
    long getMillisSinceOpen(String beanClass, String methodName);

    /**
     * Returns the number of successes recorded in HALF_OPEN.
     */
    int getSuccessesInHalfOpen(String beanClass, String methodName);
}

