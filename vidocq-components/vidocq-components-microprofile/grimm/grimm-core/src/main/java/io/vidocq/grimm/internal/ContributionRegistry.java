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
package io.vidocq.grimm.internal;

import io.vidocq.grimm.spi.gen.OpenApiContribution;

import java.util.LinkedHashSet;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Resolution of compile-time {@code $$GrimmModel} contributions — generated
 * artifacts first, the reflective annotation scan strictly as fallback (codegen
 * audit CG-06; same chain/counters pattern as cassini, cyrano and dirac):
 * module-layer-aware ServiceLoader, then the
 * {@code Class.forName(resource + "$$GrimmModel")} naming convention, then
 * {@code null} (caller scans).
 */
public final class ContributionRegistry {

    private static final System.Logger LOG = System.getLogger(ContributionRegistry.class.getName());
    private static final String SUFFIX = "$$GrimmModel";

    private static final AtomicInteger SERVICE_LOADER_HITS = new AtomicInteger();
    private static final AtomicInteger PRE_GENERATED_HITS = new AtomicInteger();
    private static final AtomicInteger SCAN_FALLBACKS = new AtomicInteger();

    private ContributionRegistry() {
        // utility
    }

    /** Returns the contribution for {@code resourceClass}, or {@code null} (scan fallback). */
    public static OpenApiContribution resolve(Class<?> resourceClass) {
        ModuleLayer layer = resourceClass.getModule().getLayer();
        if (layer != null) {
            try {
                for (OpenApiContribution contribution : ServiceLoader.load(layer, OpenApiContribution.class)) {
                    if (contribution.resourceClass() == resourceClass) {
                        SERVICE_LOADER_HITS.incrementAndGet();
                        return contribution;
                    }
                }
            } catch (java.util.ServiceConfigurationError e) {
                LOG.log(System.Logger.Level.DEBUG, () -> "OpenApiContribution layer scan failed: " + e);
            }
        }
        for (ClassLoader loader : candidateLoaders(resourceClass)) {
            try {
                for (OpenApiContribution contribution : ServiceLoader.load(OpenApiContribution.class, loader)) {
                    if (contribution.resourceClass() == resourceClass) {
                        SERVICE_LOADER_HITS.incrementAndGet();
                        return contribution;
                    }
                }
            } catch (java.util.ServiceConfigurationError e) {
                LOG.log(System.Logger.Level.DEBUG,
                        () -> "OpenApiContribution ServiceLoader scan failed on " + loader + ": " + e);
            }
        }
        String generatedName = resourceClass.getName() + SUFFIX;
        try {
            Class<?> generated = Class.forName(generatedName, true, resourceClass.getClassLoader());
            OpenApiContribution contribution =
                    (OpenApiContribution) generated.getDeclaredConstructor().newInstance();
            if (contribution.resourceClass() != resourceClass) {
                LOG.log(System.Logger.Level.WARNING,
                        () -> generatedName + " targets " + contribution.resourceClass()
                                + " instead of " + resourceClass + " — stale jar? Falling back to the scan.");
                return null;
            }
            PRE_GENERATED_HITS.incrementAndGet();
            return contribution;
        } catch (ClassNotFoundException e) {
            return null;
        } catch (ReflectiveOperationException | ClassCastException e) {
            LOG.log(System.Logger.Level.WARNING,
                    () -> "Broken OpenAPI contribution " + generatedName
                            + " — falling back to the annotation scan: " + e);
            return null;
        }
    }

    private static Iterable<ClassLoader> candidateLoaders(Class<?> resourceClass) {
        var loaders = new LinkedHashSet<ClassLoader>();
        if (resourceClass.getClassLoader() != null) loaders.add(resourceClass.getClassLoader());
        ClassLoader tccl = Thread.currentThread().getContextClassLoader();
        if (tccl != null) loaders.add(tccl);
        ClassLoader self = ContributionRegistry.class.getClassLoader();
        if (self != null) loaders.add(self);
        return loaders;
    }

    // --- observability ------------------------------------------------------

    public static void noteScanFallback() {
        SCAN_FALLBACKS.incrementAndGet();
    }

    public static int serviceLoaderHits() {
        return SERVICE_LOADER_HITS.get();
    }

    public static int preGeneratedHits() {
        return PRE_GENERATED_HITS.get();
    }

    public static int scanFallbacks() {
        return SCAN_FALLBACKS.get();
    }

    public static void resetForTests() {
        SERVICE_LOADER_HITS.set(0);
        PRE_GENERATED_HITS.set(0);
        SCAN_FALLBACKS.set(0);
    }
}
