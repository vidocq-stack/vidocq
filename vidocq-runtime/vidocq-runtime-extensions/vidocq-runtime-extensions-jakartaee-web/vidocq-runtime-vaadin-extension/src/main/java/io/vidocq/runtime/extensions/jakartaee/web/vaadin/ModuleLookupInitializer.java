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
package io.vidocq.runtime.extensions.jakartaee.web.vaadin;

import com.vaadin.flow.di.Lookup;
import com.vaadin.flow.di.LookupInitializer;
import com.vaadin.flow.server.VaadinContext;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.function.BiFunction;

/**
 * The lookup initializer Vaadin uses in place of its own: Vaadin picks the one {@code AbstractLookupInitializer} its
 * {@code @HandlesTypes} finds besides its default, and Foy finds this one through the class index of this jar.
 *
 * <p>Vaadin creates the app shell, among others, through {@code Lookup.lookup(type)}, and its own lookup calls
 * {@code ServiceLoader.load(type)} from {@code flow.server} for a type it has no registered service for: the
 * application's own {@code AppShellConfigurator} class too. {@code ServiceLoader} accepts only the types the calling
 * module declares with {@code uses}, and the descriptor {@code vauban:modularize} writes for {@code flow.server} in
 * a jlink image cannot name an application class without a dependency cycle (BUG-20261023-06). This lookup calls
 * {@code ServiceLoader} from this module, which declares the use first with {@link Module#addUses}: a module may add
 * a use to itself. On an automatic {@code flow.server} ({@code vidocq:package}) it changes nothing.
 */
public class ModuleLookupInitializer extends LookupInitializer {

    @Override
    protected Lookup createLookup(VaadinContext context, Map<Class<?>, Collection<Class<?>>> services) {
        return new ModuleLookup(services, this::instantiate);
    }

    /** Vaadin's lookup, with the services Vaadin did not register loaded from this module. */
    static final class ModuleLookup extends LookupImpl {

        ModuleLookup(Map<Class<?>, Collection<Class<?>>> services, BiFunction<Class<?>, Class<?>, Object> factory) {
            super(services, factory);
        }

        @Override
        public <T> T lookup(Class<T> serviceClass) {
            Collection<Object> registered = serviceMap.get(serviceClass);
            if (registered != null && !registered.isEmpty()) {
                return super.lookup(serviceClass);
            }
            List<T> found = load(serviceClass);
            if (found.size() > 1) {
                throw new IllegalStateException("Several implementations of " + serviceClass.getName() + ": " + found);
            }
            return found.isEmpty() ? null : found.getFirst();
        }

        @Override
        public <T> Collection<T> lookupAll(Class<T> serviceClass) {
            List<T> result = new ArrayList<>();
            Collection<Object> registered = serviceMap.get(serviceClass);
            if (registered != null) {
                registered.forEach(service -> result.add(serviceClass.cast(service)));
            }
            for (T next : load(serviceClass)) {
                if (result.stream().noneMatch(service -> service.getClass().equals(next.getClass()))) {
                    result.add(next);
                }
            }
            return result;
        }

        private static <T> List<T> load(Class<T> serviceClass) {
            ModuleLookup.class.getModule().addUses(serviceClass);
            List<T> found = new ArrayList<>();
            ServiceLoader.load(serviceClass).forEach(found::add);
            return found;
        }
    }
}
