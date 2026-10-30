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
package io.vidocq.heisenberg.internal;

import io.vidocq.heisenberg.spi.gen.FaultToleranceCompanion;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * The {@code <Bean>$$HeisenbergPolicies} companion the Heisenberg processor generated for a bean class, found once
 * per class: {@code ServiceLoader} in the bean's module layer, then through its class loaders (a strict module
 * path needs neither {@code exports} nor {@code opens} that way), then the companion's name. A bean class without
 * one has its annotations read by reflection, which {@link #reflecting} announces once per class.
 */
final class Companions {

    private static final System.Logger LOG = System.getLogger(Companions.class.getName());

    private static final ClassValue<Optional<FaultToleranceCompanion>> BY_BEAN_CLASS = new ClassValue<>() {
        @Override
        protected Optional<FaultToleranceCompanion> computeValue(Class<?> beanClass) {
            return Optional.ofNullable(lookup(beanClass));
        }
    };

    private static final ClassValue<Boolean> ANNOUNCED = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> beanClass) {
            LOG.log(System.Logger.Level.WARNING, () -> "HEISENBERG — Fault Tolerance annotations of "
                    + beanClass.getName() + " read by reflection: the class was compiled without the Heisenberg "
                    + "processor (heisenberg-processor, brought by the Vidocq Fault Tolerance codegen bundle). "
                    + "It works, but costs reflection on the first call of each method and has no ahead-of-time "
                    + "equivalent.");
            return Boolean.TRUE;
        }
    };

    private Companions() {
    }

    /** The companion of {@code beanClass}, or {@code null}. */
    static FaultToleranceCompanion of(Class<?> beanClass) {
        return BY_BEAN_CLASS.get(beanClass).orElse(null);
    }

    /** Announces, once per class, that the annotations of {@code beanClass} are read by reflection. */
    static void reflecting(Class<?> beanClass) {
        ANNOUNCED.get(beanClass);
    }

    private static FaultToleranceCompanion lookup(Class<?> beanClass) {
        ModuleLayer layer = beanClass.getModule().getLayer();
        if (layer != null) {
            FaultToleranceCompanion found = find(() -> ServiceLoader.load(layer, FaultToleranceCompanion.class),
                    beanClass);
            if (found != null) return found;
        }
        for (ClassLoader loader : loaders(beanClass)) {
            FaultToleranceCompanion found = find(() -> ServiceLoader.load(FaultToleranceCompanion.class, loader),
                    beanClass);
            if (found != null) return found;
        }
        return byName(beanClass);
    }

    private static FaultToleranceCompanion find(java.util.function.Supplier<ServiceLoader<FaultToleranceCompanion>> loader,
                                                Class<?> beanClass) {
        try {
            for (FaultToleranceCompanion companion : loader.get()) {
                if (companion.beanClass() == beanClass) return companion;
            }
        } catch (ServiceConfigurationError e) {
            LOG.log(System.Logger.Level.DEBUG, () -> "Heisenberg companions could not be listed: " + e);
        }
        return null;
    }

    /** The companion by its name, for a class path without its service file. */
    private static FaultToleranceCompanion byName(Class<?> beanClass) {
        String name = beanClass.getName() + FaultToleranceCompanion.SUFFIX;
        try {
            Class<?> type = Class.forName(name, true, beanClass.getClassLoader());
            FaultToleranceCompanion companion = (FaultToleranceCompanion) type.getDeclaredConstructor().newInstance();
            return companion.beanClass() == beanClass ? companion : null;
        } catch (ClassNotFoundException _) {
            return null;
        } catch (ReflectiveOperationException | ClassCastException | LinkageError e) {
            LOG.log(System.Logger.Level.WARNING, () -> "Heisenberg companion " + name
                    + " could not be used, the annotations are read by reflection: " + e);
            return null;
        }
    }

    private static Set<ClassLoader> loaders(Class<?> beanClass) {
        Set<ClassLoader> loaders = new LinkedHashSet<>();
        if (beanClass.getClassLoader() != null) loaders.add(beanClass.getClassLoader());
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        if (context != null) loaders.add(context);
        return loaders;
    }
}
