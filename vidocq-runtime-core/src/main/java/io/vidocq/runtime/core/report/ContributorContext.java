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
package io.vidocq.runtime.core.report;

import io.vidocq.runtime.spi.report.LaunchMode;
import io.vidocq.runtime.spi.report.StartupReportContext;
import io.vidocq.runtime.spi.report.Verbosity;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What the contributors of one boot read: its level, its launch mode, the beans of its container, the routes the
 * sections written so far declare. One per boot, used on the booting thread only.
 *
 * <p>Nothing here throws: what cannot be answered is absent. The beans are read through the container's
 * metadata; {@link #hasBeanOfType(String)} compares names and creates nothing, and a bean that
 * {@link #lookup(Class)} creates in the dependent scope is destroyed once the contributor that asked for it
 * returned.
 */
final class ContributorContext implements StartupReportContext {

    private final Verbosity verbosity;
    private final LaunchMode launchMode;
    private final BeanManager beans;
    private final List<ContributedSection> sections = new ArrayList<>();
    private final List<CreationalContext<?>> created = new ArrayList<>();
    /** The binary names of the types of every enabled bean, read on the first question. */
    private Set<String> beanTypes;

    /**
     * @param verbosity  the level of the report; {@code null} reads as {@code off}
     * @param launchMode the launch mode of the boot; {@code null} reads as {@code prod}
     * @param beans      the bean manager of the container, or {@code null} when there is none
     */
    ContributorContext(Verbosity verbosity, LaunchMode launchMode, BeanManager beans) {
        this.verbosity = verbosity == null ? Verbosity.OFF : verbosity;
        this.launchMode = launchMode == null ? LaunchMode.PROD : launchMode;
        this.beans = beans;
    }

    @Override
    public Verbosity verbosity() {
        return verbosity;
    }

    @Override
    public LaunchMode launchMode() {
        return launchMode;
    }

    @Override
    public boolean hasBeanOfType(String typeName) {
        if (typeName == null || beans == null) {
            return false;
        }
        try {
            if (beanTypes == null) {
                beanTypes = beanTypes(beans);
            }
            return beanTypes.contains(typeName);
        } catch (RuntimeException | LinkageError unreadable) {
            return false;
        }
    }

    @Override
    public <T> Optional<T> lookup(Class<T> type) {
        if (type == null || beans == null) {
            return Optional.empty();
        }
        try {
            Bean<?> bean = beans.resolve(beans.getBeans(type));
            if (bean == null) {
                return Optional.empty();
            }
            CreationalContext<?> context = beans.createCreationalContext(bean);
            created.add(context);
            return Optional.ofNullable(type.cast(beans.getReference(bean, type, context)));
        } catch (RuntimeException | LinkageError unsatisfiedAmbiguousOrFailing) {
            return Optional.empty();
        }
    }

    @Override
    public List<String> routeUrls(String handlerClassName) {
        if (handlerClassName == null) {
            return List.of();
        }
        try {
            Map<String, String> listeners = ContributedSection.listeners(sections);
            List<String> urls = new ArrayList<>();
            for (ContributedSection section : sections) {
                for (ContributedSection.Route route : section.routes()) {
                    if (route.handlerClass().equals(handlerClassName) && listeners.containsKey(route.listener())) {
                        urls.add(route.url(listeners));
                    }
                }
            }
            return List.copyOf(urls);
        } catch (RuntimeException unreadable) {
            return List.of();
        }
    }

    /** {@code section} is being written: its routes and listeners count from now on. */
    void writing(ContributedSection section) {
        sections.add(section);
    }

    /** {@code section} is dropped, its contributor having failed: its routes and listeners no longer count. */
    void dropped(ContributedSection section) {
        sections.remove(section);
    }

    /** Destroys the dependent beans {@link #lookup(Class)} created for the contributor that just returned. */
    void release() {
        for (CreationalContext<?> context : created) {
            try {
                context.release();
            } catch (RuntimeException | LinkageError ignored) {
                // a bean that fails to be destroyed is no concern of the report
            }
        }
        created.clear();
    }

    /** The binary names of the bean types of every enabled bean: its class, its interfaces, their supertypes. */
    private static Set<String> beanTypes(BeanManager beans) {
        Set<String> names = new HashSet<>();
        for (Bean<?> bean : beans.getBeans(Object.class, Any.Literal.INSTANCE)) {
            for (Type type : bean.getTypes()) {
                if (type instanceof Class<?> raw) {
                    names.add(raw.getName());
                } else if (type instanceof ParameterizedType parameterized
                        && parameterized.getRawType() instanceof Class<?> raw) {
                    names.add(raw.getName());
                }
            }
        }
        return names;
    }
}
