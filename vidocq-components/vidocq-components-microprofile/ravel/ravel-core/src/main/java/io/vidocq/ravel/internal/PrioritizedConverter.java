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
package io.vidocq.ravel.internal;

import org.eclipse.microprofile.config.spi.Converter;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

/**
 * Internal pair {priority, converter} for MP Config 3.1 §5.3 resolution.
 *
 * <p>The highest-priority converter wins; on ties, the last registered one
 * overrides the previous converter.</p>
 *
 * <p>Default application priority is <b>100</b> (§5.3); built-ins use <b>1</b>
 * ({@link BuiltInConverters#BUILT_IN_PRIORITY}).</p>
 */
record PrioritizedConverter(int priority, Converter<?> converter) {

    /** MP §5.3 default priority when {@code @Priority} is absent. */
    static final int DEFAULT_PRIORITY = 100;

    /**
     * Reads converter priority according to §5.3:
     * <ul>
     *   <li>{@code @jakarta.annotation.Priority(value)} -> that value</li>
     *   <li>otherwise -> {@link #DEFAULT_PRIORITY}</li>
     * </ul>
     *
     * <p>Uses qualified-name reflection to avoid compile-time dependency on
     * {@code jakarta.annotation} in {@code ravel-core}.</p>
     */
    static int readPriority(Converter<?> converter) {
        for (Annotation a : converter.getClass().getAnnotations()) {
            String fqn = a.annotationType().getName();
            if ("jakarta.annotation.Priority".equals(fqn)
                    || "javax.annotation.Priority".equals(fqn)) {
                try {
                    Method valueMethod = a.annotationType().getMethod("value");
                    Object v = valueMethod.invoke(a);
                    if (v instanceof Integer i) {
                        return i;
                    }
                } catch (ReflectiveOperationException ignored) {
                    // No value() accessor: fallback to default priority.
                }
            }
        }
        return DEFAULT_PRIORITY;
    }
}

