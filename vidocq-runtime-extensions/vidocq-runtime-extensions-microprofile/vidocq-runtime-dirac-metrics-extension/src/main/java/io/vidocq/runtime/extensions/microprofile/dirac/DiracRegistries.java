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
package io.vidocq.runtime.extensions.microprofile.dirac;

import io.vidocq.dirac.cdi.internal.MetricRegistryProducerBean;
import org.eclipse.microprofile.metrics.MetricRegistry;

import java.util.List;

/**
 * The three registries of Dirac, in the order the {@code metrics} section and panel show them: the application's
 * first, then the base and vendor ones.
 *
 * @param application the {@code application} registry
 * @param base        the {@code base} registry, which Dirac fills with gauges of the JVM
 * @param vendor      the {@code vendor} registry
 */
record DiracRegistries(MetricRegistry application, MetricRegistry base, MetricRegistry vendor) {

    /**
     * The registries an existing producer holds. The three were created with it, so these calls return them and
     * create nothing.
     *
     * @param producer the producer instance
     * @return its registries
     */
    static DiracRegistries of(MetricRegistryProducerBean producer) {
        return new DiracRegistries(producer.produceApplicationByType(), producer.produceBaseByType(),
                producer.produceVendorByType());
    }

    /**
     * Reads the three registries, application first.
     *
     * @return what each holds
     */
    List<MetricsScope> read() {
        return List.of(MetricsScope.read(MetricRegistry.APPLICATION_SCOPE, application),
                MetricsScope.read(MetricRegistry.BASE_SCOPE, base),
                MetricsScope.read(MetricRegistry.VENDOR_SCOPE, vendor));
    }
}
