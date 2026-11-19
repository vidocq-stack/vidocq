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

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator;

/**
 * Creates the {@link DiscoveredMetrics} of one container from the class names {@link DiracExtension}
 * passed as a parameter. The classes are loaded through the thread context class loader, the
 * container's, as the extension did when it found them.
 */
public class DiscoveredMetricsCreator implements SyntheticBeanCreator<DiscoveredMetrics> {

    static final String CLASSES = "classes";

    @Override
    public DiscoveredMetrics create(Instance<Object> lookup, Parameters params) {
        var discovered = new DiscoveredMetrics();
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        for (String name : params.get(CLASSES, String[].class, new String[0])) {
            try {
                discovered.ingest(Class.forName(name, false, loader));
            } catch (ClassNotFoundException notVisible) {
                throw new IllegalStateException("Metric class " + name
                        + " was discovered but cannot be loaded at run time: " + notVisible.getMessage(), notVisible);
            }
        }
        return discovered;
    }
}
