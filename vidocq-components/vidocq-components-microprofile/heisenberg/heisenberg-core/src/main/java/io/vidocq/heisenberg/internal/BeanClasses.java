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
 * Finds the bean class the application wrote behind the subclass a CDI container generates to
 * intercept it.
 *
 * <p>Metric names (MP FT 4.1 §9, §10), configuration keys ({@code <class>/<method>/<annotation>/<parameter>},
 * §12) and fallback methods are all keyed by the bean class. The interceptor sees the runtime
 * class of the target, which is a generated subclass under every container: Vauban's
 * {@code Foo$$Intercepted}, Weld's {@code Foo$Proxy$_$$_WeldSubclass} (also Open Liberty),
 * OpenWebBeans' {@code Foo$$OwbInterceptProxy0}. All of them carry {@code $$} in their name, which
 * javac never produces for a class written in Java source, nested ones included.</p>
 */
public final class BeanClasses {

    private BeanClasses() {
    }

    /** The first superclass of {@code runtimeClass}, itself included, that no container generated. */
    public static Class<?> userClass(Class<?> runtimeClass) {
        Class<?> current = runtimeClass;
        while (isGenerated(current) && current.getSuperclass() != null) {
            current = current.getSuperclass();
        }
        return current;
    }

    private static boolean isGenerated(Class<?> type) {
        return type.getName().contains("$$") || type.isSynthetic();
    }
}
