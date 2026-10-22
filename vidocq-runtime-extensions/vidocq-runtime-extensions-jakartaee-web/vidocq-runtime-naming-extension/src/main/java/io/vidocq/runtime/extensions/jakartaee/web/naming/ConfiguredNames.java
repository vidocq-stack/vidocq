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
package io.vidocq.runtime.extensions.jakartaee.web.naming;

import io.vidocq.vauban.naming.Binder;
import io.vidocq.vauban.naming.JndiBindings;
import io.vidocq.vauban.naming.JndiTarget;
import jakarta.enterprise.context.Dependent;

import java.util.Map;

/**
 * The java: names declared in {@code vidocq.properties}, as a {@link JndiBindings} bean: a value starting with
 * {@code java:} is a link to that name, any other value a bean written as text. Vauban checks them when the
 * container starts.
 *
 * <p>The bean is in the module's build-time index and created by its generated provider; {@link NamingExtension}
 * publishes the declarations before the container starts. Without {@code vidocq.naming.*} keys it binds nothing.
 * {@code @Dependent}: the naming service destroys it once it has bound the names.
 */
@Dependent
public class ConfiguredNames implements JndiBindings {

    /** Name to target, as written; published by {@link NamingExtension#configure}. */
    private static volatile Map<String, String> declarations = Map.of();

    /** Publishes the names the next container declares. */
    static void declare(Map<String, String> names) {
        declarations = Map.copyOf(names);
    }

    @Override
    public void bind(Binder binder) {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        declarations.forEach((name, target) -> binder.bind(name,
                target.startsWith("java:") ? JndiTarget.link(target) : JndiTarget.bean(target, loader)));
    }
}
