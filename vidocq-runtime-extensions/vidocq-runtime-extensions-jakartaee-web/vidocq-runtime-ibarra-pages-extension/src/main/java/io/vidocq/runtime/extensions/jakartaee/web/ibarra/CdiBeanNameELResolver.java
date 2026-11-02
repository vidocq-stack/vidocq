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
package io.vidocq.runtime.extensions.jakartaee.web.ibarra;

import jakarta.el.ELContext;
import jakarta.el.ELResolver;
import jakarta.el.PropertyNotWritableException;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Resolves the top-level names of EL expressions to CDI beans by bean name ({@code @Named}), as Jakarta EE requires
 * of a page: {@code ${cart}} is the contextual reference of the bean named {@code cart}. Read-only; a name no bean
 * has is left to the next resolver of the chain. CDI Lite has no {@code BeanManager.getELResolver()}, so the
 * Pages extension provides this one, built on {@link BeanManager#getBeans(String)}.
 */
public final class CdiBeanNameELResolver extends ELResolver {

    /** Finds the bean a name designates: a supplier of its contextual reference, or empty. */
    @FunctionalInterface
    public interface BeanNames {
        /**
         * @param name    a top-level EL name
         * @param context the evaluation context, where a {@code @Dependent} instance records the request it belongs to
         * @return the supplier of the reference of the bean named {@code name}, empty when no bean has that name
         */
        Optional<Supplier<Object>> find(String name, ELContext context);
    }

    private final BeanNames names;

    /**
     * @param names how a name finds its bean
     */
    public CdiBeanNameELResolver(BeanNames names) {
        this.names = Objects.requireNonNull(names, "names");
    }

    /**
     * The bean names of a CDI container. The bean a name designates is looked up once; its contextual reference is
     * asked on each evaluation, so a normal-scoped bean yields its client proxy and the current instance answers. A
     * {@code @Dependent} instance belongs to the page's request and is destroyed when it ends
     * ({@link RequestDependents}).
     *
     * @param beanManager the CDI container
     * @return its bean names
     */
    public static BeanNames of(BeanManager beanManager) {
        Objects.requireNonNull(beanManager, "beanManager");
        var resolved = new ConcurrentHashMap<String, Optional<Bean<?>>>();
        return (name, context) -> resolved
                .computeIfAbsent(name, n -> Optional.ofNullable(beanManager.resolve(beanManager.getBeans(n))))
                .map(bean -> () -> {
                    CreationalContext<?> creational = beanManager.createCreationalContext(bean);
                    Object reference = beanManager.getReference(bean, Object.class, creational);
                    if (bean.getScope() == Dependent.class) RequestDependents.track(context, creational);
                    return reference;
                });
    }

    private Optional<Supplier<Object>> named(ELContext context, Object base, Object property) {
        return base == null && property instanceof String name ? names.find(name, context) : Optional.empty();
    }

    @Override
    public Object getValue(ELContext context, Object base, Object property) {
        Objects.requireNonNull(context, "context");
        Optional<Supplier<Object>> bean = named(context, base, property);
        if (bean.isEmpty()) return null;
        context.setPropertyResolved(base, property);
        return bean.get().get();
    }

    @Override
    public Class<?> getType(ELContext context, Object base, Object property) {
        Objects.requireNonNull(context, "context");
        if (named(context, base, property).isPresent()) context.setPropertyResolved(base, property);
        // Read-only: the type of a resolved name is null (ELResolver.getType contract).
        return null;
    }

    @Override
    public void setValue(ELContext context, Object base, Object property, Object value) {
        Objects.requireNonNull(context, "context");
        if (named(context, base, property).isPresent()) {
            throw new PropertyNotWritableException("The CDI bean " + property + " cannot be replaced from EL");
        }
    }

    @Override
    public boolean isReadOnly(ELContext context, Object base, Object property) {
        Objects.requireNonNull(context, "context");
        if (named(context, base, property).isEmpty()) return false;
        context.setPropertyResolved(base, property);
        return true;
    }

    @Override
    public Class<?> getCommonPropertyType(ELContext context, Object base) {
        return base == null ? String.class : null;
    }
}
