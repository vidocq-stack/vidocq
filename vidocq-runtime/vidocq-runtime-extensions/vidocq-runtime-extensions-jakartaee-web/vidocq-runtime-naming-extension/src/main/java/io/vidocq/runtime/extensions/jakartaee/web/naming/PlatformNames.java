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
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Inject;

import java.lang.annotation.Annotation;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * The names the Jakarta EE platform gives its transaction objects, bound to the container's beans: an application or
 * a library that looks up {@code java:comp/UserTransaction} (Jakarta EE 11 Platform §5.10) or
 * {@code java:comp/TransactionSynchronizationRegistry} (§5.11) gets the bean the transactions extension produces.
 *
 * <p>A name is bound only when the application has its type and the container a bean of it, so an application without
 * transactions declares nothing that fails its start; a name {@code vidocq.properties} declares is left to it. The
 * names take the bean written as text, so this module needs no transaction API. {@code @Dependent}: the naming
 * service destroys it once it has bound the names.
 */
@Dependent
public class PlatformNames implements JndiBindings {

    /** The names, to the type of the bean each one is bound to. */
    static final Map<String, String> TRANSACTION_NAMES = Map.of(
            "java:comp/UserTransaction", "jakarta.transaction.UserTransaction",
            "java:comp/TransactionSynchronizationRegistry", "jakarta.transaction.TransactionSynchronizationRegistry");

    private final Map<String, String> names;
    private final Predicate<JndiTarget.Bean> resolvable;

    /**
     * The platform names, bound when the container has a bean of their type.
     *
     * @param beanManager the running container
     */
    @Inject
    public PlatformNames(BeanManager beanManager) {
        this(TRANSACTION_NAMES, bean -> !beanManager.getBeans(bean.type(), bean.qualifiers().toArray(Annotation[]::new)).isEmpty());
    }

    PlatformNames(Map<String, String> names, Predicate<JndiTarget.Bean> resolvable) {
        this.names = Map.copyOf(names);
        this.resolvable = Objects.requireNonNull(resolvable, "resolvable");
    }

    @Override
    public void bind(Binder binder) {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        names.forEach((name, type) -> {
            if (!ConfiguredNames.declares(name)) {
                bean(type, loader).filter(resolvable).ifPresent(bean -> binder.bind(name, bean));
            }
        });
    }

    /** The default bean of {@code type}, or empty when the application does not have that type. */
    private static Optional<JndiTarget.Bean> bean(String type, ClassLoader loader) {
        try {
            return Optional.of((JndiTarget.Bean) JndiTarget.bean(type, loader));
        } catch (IllegalArgumentException _) {
            return Optional.empty();
        }
    }
}
