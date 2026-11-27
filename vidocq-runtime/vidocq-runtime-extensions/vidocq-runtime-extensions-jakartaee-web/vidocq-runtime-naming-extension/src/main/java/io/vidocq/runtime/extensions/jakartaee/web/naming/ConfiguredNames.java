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
import io.vidocq.vauban.naming.JndiName;
import io.vidocq.vauban.naming.JndiTarget;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;

import javax.naming.InvalidNameException;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/**
 * The java: names declared in {@code vidocq.properties}, as a {@link JndiBindings} bean: a value starting with
 * {@code java:} is a link to that name, any other value a bean written as text. Vauban checks them when the
 * container starts.
 *
 * <p>A link may name a name the pools answer ({@link PoolNames}), such as
 * {@code vidocq.naming.comp/env/jdbc/orders = java:comp/env/orders}: such a link, or a chain of links of
 * {@code vidocq.properties} that ends on such a name, is left to {@link PoolNames}, which answers it with the data
 * source of that name, through the transactions of the application (Vidocq/vidocq#831). Any other link is bound, and
 * Vauban fails the start when it names a name nothing declares.
 *
 * <p>The bean is in the module's build-time index and created by its generated provider; {@link NamingExtension}
 * publishes the declarations before the container starts. Without {@code vidocq.naming.*} keys it binds nothing.
 * {@code @Dependent}: the naming service destroys it once it has bound the names.
 */
@Dependent
public class ConfiguredNames implements JndiBindings {

    /** Name to target, as written; published by {@link NamingExtension#configure}. */
    private static final AtomicReference<Map<String, String>> DECLARATIONS = new AtomicReference<>(Map.of());

    /** Whether the pools answer a name. */
    private final Predicate<JndiName> pooled;

    /** Publishes the names the next container declares. */
    static void declare(Map<String, String> names) {
        DECLARATIONS.set(Map.copyOf(names));
    }

    /** Whether {@code vidocq.properties} declares this name, which a default of the platform then leaves to it. */
    static boolean declares(String name) {
        return DECLARATIONS.get().containsKey(name);
    }

    /** The declared names. */
    static Set<String> names() {
        return DECLARATIONS.get().keySet();
    }

    /**
     * Where the links of {@code vidocq.properties} lead from a declared name: the first name of the chain that
     * {@code vidocq.properties} does not declare.
     *
     * @param name a name, such as {@code java:comp/env/jdbc/orders}
     * @return that name, or empty when {@code name} is not a declared link, when its chain ends on a bean, loops or
     *         names an invalid name
     */
    static Optional<JndiName> linkedTo(String name) {
        Map<String, String> declared = DECLARATIONS.get();
        Set<String> followed = new HashSet<>();
        String current = name;
        while (declared.containsKey(current) && followed.add(current)) {
            String target = declared.get(current);
            if (!target.startsWith("java:")) {
                return Optional.empty();
            }
            JndiName linked;
            try {
                linked = JndiName.parse(target);
            } catch (InvalidNameException _) {
                return Optional.empty();
            }
            current = linked.toString();
            if (!declared.containsKey(current)) {
                return Optional.of(linked);
            }
        }
        return Optional.empty();
    }

    /**
     * The declared names, those the pools answer left to them.
     *
     * @param pools the names of the pools of the running container
     */
    @Inject
    public ConfiguredNames(PoolNames pools) {
        this(pools::answers);
    }

    ConfiguredNames(Predicate<JndiName> pooled) {
        this.pooled = Objects.requireNonNull(pooled, "pooled");
    }

    @Override
    public void bind(Binder binder) {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        DECLARATIONS.get().forEach((name, target) -> {
            if (!target.startsWith("java:")) {
                binder.bind(name, JndiTarget.bean(target, loader));
            } else if (linkedTo(name).filter(pooled).isEmpty()) {
                binder.bind(name, JndiTarget.link(target));
            }
        });
    }
}
