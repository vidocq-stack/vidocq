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
package io.vidocq.runtime.extensions.jakartaee.web.noether.persistence.dev;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Stubs of the Jakarta interfaces the panel calls, each answering from a map of its methods by name. */
final class Stubs {

    /** What an answer returns for the stub to return itself, as a query's setters do. */
    static final Object SELF = new Object();

    /** An answer of a {@code void} method. */
    static final Function<Object[], Object> NOTHING = args -> null;

    private Stubs() {
    }

    /**
     * A {@code type} whose methods answer from {@code answers} by their name, each call added to {@code calls}; a
     * method without an answer throws {@link UnsupportedOperationException}.
     */
    static <T> T of(Class<T> type, List<String> calls, Map<String, Function<Object[], Object>> answers) {
        return type.cast(Proxy.newProxyInstance(Stubs.class.getClassLoader(), new Class<?>[] {type},
                (proxy, method, args) -> {
                    String name = method.getName();
                    switch (name) {
                        case "toString":
                            return type.getSimpleName() + " stub";
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == args[0];
                        default:
                            break;
                    }
                    calls.add(name);
                    Function<Object[], Object> answer = answers.get(name);
                    if (answer == null) {
                        throw new UnsupportedOperationException(type.getSimpleName() + "." + name);
                    }
                    Object answered = answer.apply(args == null ? new Object[0] : args);
                    return answered == SELF ? proxy : answered;
                }));
    }
}
