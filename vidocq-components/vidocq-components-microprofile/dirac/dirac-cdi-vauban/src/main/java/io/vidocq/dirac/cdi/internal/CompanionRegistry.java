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
package io.vidocq.dirac.cdi.internal;

import io.vidocq.dirac.spi.gen.MetricsCompanion;

import java.util.LinkedHashSet;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Resolution of compile-time {@code $$DiracMetrics} companions — generated
 * artifacts first, the reflective startup scan strictly as fallback (codegen
 * audit CG-05, same chain/counters pattern as cassini's AdapterRegistry and
 * cyrano's ClientProxyRegistry):
 *
 * <ol>
 *   <li><strong>ServiceLoader</strong> of {@link MetricsCompanion} — module-layer
 *       aware, so a strict Java module only declares
 *       {@code provides MetricsCompanion with com.acme.MyBean$$DiracMetrics};</li>
 *   <li><strong>Naming convention</strong> —
 *       {@code Class.forName(bean.getName() + "$$DiracMetrics")};</li>
 *   <li><strong>Fallback</strong> — {@code null}: the caller runs the
 *       {@code DiracExtension} annotation scan.</li>
 * </ol>
 */
final class CompanionRegistry {

    private static final System.Logger LOG = System.getLogger(CompanionRegistry.class.getName());
    private static final String SUFFIX = "$$DiracMetrics";

    private static final AtomicInteger SERVICE_LOADER_HITS = new AtomicInteger();
    private static final AtomicInteger PRE_GENERATED_HITS = new AtomicInteger();
    private static final AtomicInteger SCAN_FALLBACKS = new AtomicInteger();

    private CompanionRegistry() {
        // utility
    }

    /** Returns the companion for {@code beanClass}, or {@code null} (scan fallback). */
    static MetricsCompanion resolve(Class<?> beanClass) {
        ModuleLayer layer = beanClass.getModule().getLayer();
        if (layer != null) {
            try {
                for (MetricsCompanion companion : ServiceLoader.load(layer, MetricsCompanion.class)) {
                    if (companion.beanClass() == beanClass) {
                        SERVICE_LOADER_HITS.incrementAndGet();
                        return companion;
                    }
                }
            } catch (java.util.ServiceConfigurationError e) {
                LOG.log(System.Logger.Level.DEBUG, () -> "MetricsCompanion layer scan failed: " + e);
            }
        }
        for (ClassLoader loader : candidateLoaders(beanClass)) {
            try {
                for (MetricsCompanion companion : ServiceLoader.load(MetricsCompanion.class, loader)) {
                    if (companion.beanClass() == beanClass) {
                        SERVICE_LOADER_HITS.incrementAndGet();
                        return companion;
                    }
                }
            } catch (java.util.ServiceConfigurationError e) {
                LOG.log(System.Logger.Level.DEBUG,
                        () -> "MetricsCompanion ServiceLoader scan failed on " + loader + ": " + e);
            }
        }
        String generatedName = beanClass.getName() + SUFFIX;
        try {
            Class<?> generated = Class.forName(generatedName, true, beanClass.getClassLoader());
            MetricsCompanion companion = (MetricsCompanion) generated.getDeclaredConstructor().newInstance();
            if (companion.beanClass() != beanClass) {
                LOG.log(System.Logger.Level.WARNING,
                        () -> generatedName + " targets " + companion.beanClass()
                                + " instead of " + beanClass + " — stale jar? Falling back to the scan.");
                return null;
            }
            PRE_GENERATED_HITS.incrementAndGet();
            return companion;
        } catch (ClassNotFoundException e) {
            return null;
        } catch (ReflectiveOperationException | ClassCastException e) {
            LOG.log(System.Logger.Level.WARNING,
                    () -> "Broken metrics companion " + generatedName
                            + " — falling back to the annotation scan: " + e);
            return null;
        }
    }

    private static Iterable<ClassLoader> candidateLoaders(Class<?> beanClass) {
        var loaders = new LinkedHashSet<ClassLoader>();
        if (beanClass.getClassLoader() != null) loaders.add(beanClass.getClassLoader());
        ClassLoader tccl = Thread.currentThread().getContextClassLoader();
        if (tccl != null) loaders.add(tccl);
        ClassLoader self = CompanionRegistry.class.getClassLoader();
        if (self != null) loaders.add(self);
        return loaders;
    }

    // --- observability ------------------------------------------------------

    static void noteScanFallback() {
        SCAN_FALLBACKS.incrementAndGet();
    }

    static int serviceLoaderHits() {
        return SERVICE_LOADER_HITS.get();
    }

    static int preGeneratedHits() {
        return PRE_GENERATED_HITS.get();
    }

    static int scanFallbacks() {
        return SCAN_FALLBACKS.get();
    }

    static void resetForTests() {
        SERVICE_LOADER_HITS.set(0);
        PRE_GENERATED_HITS.set(0);
        SCAN_FALLBACKS.set(0);
    }
}
