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
package io.vidocq.heisenberg.tck.arquillian;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.eclipse.microprofile.fault.tolerance.tck.metrics.util.MetricRegistryProxy;
import org.eclipse.microprofile.fault.tolerance.tck.metrics.util.MetricRegistryProxyHandler;
import org.eclipse.microprofile.metrics.MetricRegistry;
import org.eclipse.microprofile.metrics.annotation.RegistryType;

import java.lang.reflect.Proxy;

/**
 * Produces a {@code @Default} {@link MetricRegistryProxy} bean that wraps the
 * Dirac APPLICATION registry, required by the MicroProfile Fault Tolerance metrics TCK tests.
 *
 * <p>FT metrics are published in the APPLICATION registry by {@code DiracFtMetricsRecorder}.
 * This producer exposes that registry through the TCK {@code MetricRegistryProxy} interface with
 * qualifier {@code @Default} (the TCK tests inject without a qualifier).</p>
 */
@ApplicationScoped
public class MetricRegistryProxyProducerBean {

    @SuppressWarnings("deprecation")
    @Inject
    @RegistryType
    private MetricRegistry applicationRegistry;

    @Produces
    public MetricRegistryProxy produce() {
        return buildProxy();
    }

    /**
     * Qualified producer for TCK tests that inject
     * {@code @Inject @RegistryType(type=Type.BASE) MetricRegistryProxy} (cf.
     * {@code AllMetricsTest.testMetricUnits}).
     *
     * <p>In MP Metrics 5, the BASE/APPLICATION/VENDOR distinction is deprecated — all
     * metrics coexist in the same registry. We therefore expose the same Dirac registry
     * under the qualifier expected by the TCK.</p>
     */
    @SuppressWarnings("deprecation")
    @Produces
    @RegistryType(type = MetricRegistry.Type.BASE)
    public MetricRegistryProxy produceBase() {
        return buildProxy();
    }

    /**
     * Producer of {@link MetricRegistry} qualified with {@code @RegistryType(BASE)} — required by
     * the TCK {@code MetricRegistryProvider} bean, which uses
     * {@code CDI.current().select(MetricRegistry.class, RegistryTypeLiteral.BASE)} to
     * build its own {@code MetricRegistryProxy}. Dirac exposes only the
     * {@code @RegistryType()} registry (Type.APPLICATION by default) — we re-expose the same
     * instance with the BASE qualifier expected by the MP Metrics 4.x spec.
     */
    @SuppressWarnings("deprecation")
    @Produces
    @RegistryType(type = MetricRegistry.Type.BASE)
    public MetricRegistry produceBaseRegistry() {
        return applicationRegistry;
    }

    private MetricRegistryProxy buildProxy() {
        return (MetricRegistryProxy) Proxy.newProxyInstance(
                MetricRegistryProxy.class.getClassLoader(),
                new Class<?>[] { MetricRegistryProxy.class },
                new MetricRegistryProxyHandler(applicationRegistry)
        );
    }
}
