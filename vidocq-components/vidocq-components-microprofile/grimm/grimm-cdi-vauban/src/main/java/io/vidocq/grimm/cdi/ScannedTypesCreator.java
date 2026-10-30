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
package io.vidocq.grimm.cdi;

import io.vidocq.grimm.spi.gen.OpenApiContribution;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator;
import java.util.LinkedHashSet;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * Creates the {@link ScannedTypes} of one container: the classes {@link GrimmExtension} recorded,
 * plus every resource class the Grimm annotation processor described in the application's
 * {@code META-INF/services/io.vidocq.grimm.spi.gen.OpenApiContribution}.
 *
 * <p>The second source makes the document complete in {@code annotated} discovery mode: there, a
 * {@code @Path} class with no bean-defining annotation is not a bean, so the extension never sees
 * it (grimm#22). Both are loaded through the thread context class loader, the application's.</p>
 */
public class ScannedTypesCreator implements SyntheticBeanCreator<ScannedTypes> {

    static final String CLASSES = "classes";

    private static final System.Logger LOG = System.getLogger(ScannedTypesCreator.class.getName());

    @Override
    public ScannedTypes create(Instance<Object> lookup, Parameters params) {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            loader = ScannedTypesCreator.class.getClassLoader();
        }
        Set<Class<?>> classes = new LinkedHashSet<>();
        for (String name : params.get(CLASSES, String[].class, new String[0])) {
            try {
                classes.add(Class.forName(name, false, loader));
            } catch (ClassNotFoundException | LinkageError _) {
                // The container's own types (an application server's beans) are not the application's.
                LOG.log(System.Logger.Level.DEBUG, "Scanned class {0} is not visible to the application", name);
            }
        }
        try {
            for (OpenApiContribution contribution : ServiceLoader.load(OpenApiContribution.class, loader)) {
                Class<?> resource = contribution.resourceClass();
                if (GrimmExtension.documented(resource.getName())) {
                    classes.add(resource);
                }
            }
        } catch (ServiceConfigurationError e) {
            LOG.log(System.Logger.Level.DEBUG, () -> "OpenApiContribution services could not be read: " + e);
        }
        addDefinitionClasses(loader, classes);
        return ScannedTypes.of(classes);
    }

    /**
     * Adds the classes grimm-processor listed for their {@code @OpenAPIDefinition}: in {@code annotated} discovery
     * mode an {@code Application} subclass is no bean, so the extension never sees it (BUG-20261030-02).
     */
    private static void addDefinitionClasses(ClassLoader loader, Set<Class<?>> classes) {
        try {
            var lists = loader.getResources(OpenApiContribution.DEFINITIONS);
            while (lists.hasMoreElements()) {
                try (var in = lists.nextElement().openStream()) {
                    for (String line : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                            .split("\\R")) {
                        addListed(line.strip(), loader, classes);
                    }
                }
            }
        } catch (java.io.IOException e) {
            LOG.log(System.Logger.Level.DEBUG, () -> "OpenAPI definition lists could not be read: " + e);
        }
    }

    /** Adds the class grimm-processor listed under {@code name}, a binary name read from the application's jar. */
    private static void addListed(String name, ClassLoader loader, Set<Class<?>> classes) {
        if (name.isEmpty() || !GrimmExtension.documented(name)) {
            return;
        }
        try {
            classes.add(Class.forName(name, false, loader));
        } catch (ClassNotFoundException | LinkageError _) {
            LOG.log(System.Logger.Level.DEBUG, "Listed definition class {0} is not visible", name);
        }
    }
}
