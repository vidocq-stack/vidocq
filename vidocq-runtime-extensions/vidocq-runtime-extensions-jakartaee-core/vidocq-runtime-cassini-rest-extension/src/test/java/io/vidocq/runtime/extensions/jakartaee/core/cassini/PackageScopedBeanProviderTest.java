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
package io.vidocq.runtime.extensions.jakartaee.core.cassini;

import io.vidocq.cassini.spi.bean.BeanProvider;
import io.vidocq.runtime.extensions.jakartaee.core.cassini.CassiniMountHandlerProvider.PackageScopedBeanProvider;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** A mount's {@code include-packages} and {@code exclude-packages}: what resource classes each mount serves. */
class PackageScopedBeanProviderTest {

    /** The resource classes of the whole application, from four packages. */
    private static final Set<Class<?>> ALL = new LinkedHashSet<>(List.of(String.class, List.class, ArrayList.class,
            Callable.class));

    /** A bean index that knows the resource classes only. */
    private record Index(Set<Class<?>> resources) implements BeanProvider {

        @Override
        public <T> T getBean(Class<T> type) {
            return null;
        }

        @Override
        public Object contextualInstance(Class<?> type, Object bean) {
            return bean;
        }

        @Override
        public Set<Class<?>> getResourceClasses() {
            return resources;
        }
    }

    @Test
    void aPackageIncludesItsSubPackagesUnlessTheyAreExcluded() {
        BeanProvider scoped = new PackageScopedBeanProvider(new Index(ALL), List.of("java.util"),
                List.of("java.util.concurrent"));

        assertEquals(Set.of(List.class, ArrayList.class), scoped.getResourceClasses());
    }

    @Test
    void withoutIncludesEveryPackageButTheExcludedOnesIsServed() {
        BeanProvider scoped = new PackageScopedBeanProvider(new Index(ALL), List.of(), List.of("java.util"));

        assertEquals(Set.of(String.class), scoped.getResourceClasses());
    }

    @Test
    void aPrefixMatchesWholePackageNamesOnly() {
        BeanProvider scoped = new PackageScopedBeanProvider(new Index(ALL), List.of("java.util.conc", "java.lang"),
                List.of());

        assertEquals(Set.of(String.class), scoped.getResourceClasses());
    }

    @Test
    void beansAreLookedUpInTheWholeIndex() {
        Object bean = new Object();
        BeanProvider scoped = new PackageScopedBeanProvider(new Index(ALL), List.of("java.lang"), List.of());

        assertSame(bean, scoped.contextualInstance(Object.class, bean));
        assertNull(scoped.getBean(String.class));
    }
}
