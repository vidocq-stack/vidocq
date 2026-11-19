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

import io.vidocq.cassini.spi.gen.ApplicationDescriptor;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.ServiceLoader;

/**
 * The {@code @ApplicationPath} REST is mounted on. The descriptors {@code cassini-processor} generates
 * come first: they need no bean, and an {@code Application} without a scope is no bean under
 * {@code annotated} discovery (Vidocq/vidocq#219). The {@code Application} beans remain the source for
 * classes compiled without the processor.
 */
final class ApplicationPaths {

    private static final System.Logger LOG = System.getLogger(ApplicationPaths.class.getName());

    private ApplicationPaths() {}

    /** The descriptors {@code loader} sees, then the beans of {@code beanManager}. */
    static String resolve(ClassLoader loader, BeanManager beanManager) {
        List<ApplicationDescriptor> descriptors = new ArrayList<>();
        ServiceLoader.load(ApplicationDescriptor.class, loader).forEach(descriptors::add);
        return resolve(descriptors, beanManager);
    }

    /**
     * @return the normalized path ({@code /api}), or {@code null} for the root or when no
     *         {@code Application} declares one; with several, the first by class name
     */
    static String resolve(List<ApplicationDescriptor> descriptors, BeanManager beanManager) {
        List<ApplicationDescriptor> sorted = descriptors.stream()
                .sorted(Comparator.comparing(d -> d.applicationClass().getName()))
                .toList();
        if (!sorted.isEmpty()) {
            ApplicationDescriptor first = sorted.getFirst();
            if (sorted.size() > 1) {
                LOG.log(System.Logger.Level.WARNING, "{0} Application classes declare an @ApplicationPath; "
                        + "mounting the one of {1}", sorted.size(), first.applicationClass().getName());
            }
            return normalize(first.applicationPath(), first.applicationClass());
        }
        if (beanManager == null) return null;
        for (Bean<?> bean : beanManager.getBeans(Object.class, Any.Literal.INSTANCE)) {
            Class<?> beanClass = bean.getBeanClass();
            if (beanClass == null || !Application.class.isAssignableFrom(beanClass)) continue;
            ApplicationPath annotation = beanClass.getAnnotation(ApplicationPath.class);
            if (annotation != null) return normalize(annotation.value(), beanClass);
        }
        return null;
    }

    private static String normalize(String value, Class<?> source) {
        if (value == null || value.isBlank() || "/".equals(value)) return null;
        String normalized = value.startsWith("/") ? value : "/" + value;
        if (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        if (normalized.isEmpty()) return null;
        LOG.log(System.Logger.Level.INFO, "Mounting at @ApplicationPath {0} from {1}", normalized, source.getName());
        return normalized;
    }
}
