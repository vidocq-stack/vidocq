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
package io.vidocq.mansart.data.core;

import io.vidocq.mansart.data.dialect.EntityModel;

import java.util.List;
import java.util.ServiceLoader;

/**
 * Finds the {@link GeneratedComponents} of a type's package, through {@link ServiceLoader} from the type's class
 * loader, as Vauban finds its generated providers (MANSART-038). The providers of a loader are loaded once. Public for
 * Mansart's other modules (the CDI extension); applications have no use for it.
 */
public final class Generated {

    private static final ClassValue<List<GeneratedComponents>> PROVIDERS = new ClassValue<>() {
        @Override
        protected List<GeneratedComponents> computeValue(Class<?> type) {
            ClassLoader loader = type.getClassLoader();
            if (loader == null) {
                return List.of();
            }
            String pkg = type.getPackageName();
            try {
                return ServiceLoader.load(GeneratedComponents.class, loader).stream()
                        .filter(p -> p.type().getPackageName().equals(pkg))
                        .map(ServiceLoader.Provider::get)
                        .toList();
            } catch (java.util.ServiceConfigurationError _) {
                return List.of();
            }
        }
    };

    private Generated() {
    }

    /**
     * The name of the implementation {@code mansart-data-processor} generates for the repository of this binary name:
     * the name plus {@code Impl}, with {@code _} for the {@code $} of a nested interface ({@code demo.Kennel$Pets}
     * gives {@code demo.Kennel_PetsImpl}), since the implementation is a top-level class of the repository's package
     * and a {@code $} in its name would read as nesting (mansart#85). The processor applies the same rule.
     */
    public static String implementationName(String repositoryBinaryName) {
        return repositoryBinaryName.replace('$', '_') + "Impl";
    }

    /** The generated model of {@code entity}, or {@code null}. */
    public static <T> EntityModel<T> model(Class<T> entity) {
        for (GeneratedComponents components : PROVIDERS.get(entity)) {
            EntityModel<T> model = components.model(entity);
            if (model != null) {
                return model;
            }
        }
        return null;
    }

    /** A generated implementation of {@code repository} on {@code runtime}, or {@code null}. */
    public static Object repository(Class<?> repository, RepositoryRuntime runtime) {
        for (GeneratedComponents components : PROVIDERS.get(repository)) {
            Object implementation = components.repository(repository, runtime);
            if (implementation != null) {
                return implementation;
            }
        }
        return null;
    }
}
