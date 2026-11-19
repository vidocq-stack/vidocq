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

import jakarta.enterprise.inject.Instance;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test-only CDI {@link Instance} that resolves to one fixed value (or to nothing when the value is
 * {@code null}); {@code select} returns the same instance, {@code destroy} is recorded.
 */
final class FixedInstance {

    private final Object value;
    private final List<Object> destroyed = new CopyOnWriteArrayList<>();

    FixedInstance(Object value) {
        this.value = value;
    }

    @SuppressWarnings("unchecked")
    <T> Instance<T> instance() {
        return (Instance<T>) Proxy.newProxyInstance(Instance.class.getClassLoader(),
                new Class<?>[]{Instance.class}, (self, method, args) -> switch (method.getName()) {
                    case "select" -> self;
                    case "isResolvable" -> value != null;
                    case "isUnsatisfied" -> value == null;
                    case "get" -> value;
                    case "iterator" -> (value == null ? List.of() : List.of(value)).iterator();
                    case "destroy" -> destroyed.add(args[0]);
                    case "toString" -> "FixedInstance[" + value + "]";
                    case "hashCode" -> System.identityHashCode(self);
                    case "equals" -> self == args[0];
                    default -> throw new UnsupportedOperationException(method.toString());
                });
    }

    List<Object> destroyed() {
        return destroyed;
    }
}
