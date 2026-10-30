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
package io.vidocq.heisenberg.spi.gen;

import org.eclipse.microprofile.faulttolerance.Asynchronous;
import org.eclipse.microprofile.faulttolerance.Bulkhead;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;

/**
 * The Fault Tolerance annotations of one bean class, resolved at compile time: implemented by the
 * {@code <Bean>$$HeisenbergPolicies} companion the Heisenberg annotation processor generates.
 *
 * <p>For each method an interceptor can guard, the companion answers what {@code AnnotationReader} answers by
 * reflection: each annotation from the method's nearest declaration in the class hierarchy, else from the nearest
 * class that carries it (the six annotations are {@code @Inherited}). Values are the annotation's, defaults filled
 * in; MicroProfile Config overrides are applied at run time, as for annotations read by reflection.</p>
 *
 * <p>Found through {@code ServiceLoader} ({@code provides ... with}, or {@code META-INF/services} which the Vidocq
 * application layer turns into {@code provides}), then by the {@code Class.forName(bean + "$$HeisenbergPolicies")}
 * naming convention. A bean class without a companion is read by reflection, and announced once.</p>
 *
 * <p>Implementations are stateless, with a public no-argument constructor.</p>
 */
public interface FaultToleranceCompanion {

    /** Suffix of the generated companion's binary name. */
    String SUFFIX = "$$HeisenbergPolicies";

    /** The bean class these policies belong to. */
    Class<?> beanClass();

    /**
     * The annotations of the method {@code key} names, or {@code null} when it carries none, directly or through
     * its class.
     *
     * @param key the method name and its erased parameter types, as {@link #methodKey} writes them
     */
    Policies policies(String key);

    /**
     * The key of a method: its name, then its erased parameter types between parentheses, separated by commas,
     * each as {@link Class#getTypeName()} writes it ({@code java.lang.String[]}, {@code a.Outer$Inner}).
     */
    static String methodKey(String name, Class<?>... parameterTypes) {
        StringBuilder key = new StringBuilder(name).append('(');
        for (int i = 0; i < parameterTypes.length; i++) {
            if (i > 0) key.append(',');
            key.append(parameterTypes[i].getTypeName());
        }
        return key.append(')').toString();
    }

    /** The six annotations of one method, each {@code null} when absent. */
    record Policies(Retry retry, Timeout timeout, CircuitBreaker circuitBreaker, Bulkhead bulkhead,
                    Fallback fallback, Asynchronous asynchronous) {
    }
}
