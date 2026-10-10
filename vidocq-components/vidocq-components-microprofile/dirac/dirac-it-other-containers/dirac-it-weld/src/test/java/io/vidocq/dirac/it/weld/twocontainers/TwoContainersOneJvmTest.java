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
package io.vidocq.dirac.it.weld.twocontainers;

import io.vidocq.dirac.cdi.internal.CountedInterceptor;
import io.vidocq.dirac.cdi.internal.GaugeRegistrationBean;
import io.vidocq.dirac.cdi.internal.MetricRegistryProducerBean;
import io.vidocq.dirac.cdi.internal.TimedInterceptor;
import java.util.Set;
import java.util.stream.Collectors;
import org.eclipse.microprofile.metrics.MetricRegistry;
import org.jboss.weld.environment.se.Weld;
import org.jboss.weld.environment.se.WeldContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Two containers in one JVM, sharing the Dirac classes, keep their metrics apart (dirac#23): the
 * discovery state belongs to each container, not to a static field of the extension.
 */
class TwoContainersOneJvmTest {

    private static WeldContainer first;
    private static volatile WeldContainer second;

    private static final Class<?>[] DIRAC_BEANS = {
            GaugeRegistrationBean.class, MetricRegistryProducerBean.class,
            CountedInterceptor.class, TimedInterceptor.class};

    @BeforeAll
    static void start() {
        first = new Weld("first").disableDiscovery()
                .addBeanClasses(DIRAC_BEANS).addBeanClasses(GaugeA.class, SecondContainerBooter.class)
                .initialize();
    }

    static void startSecond() {
        second = new Weld("second").disableDiscovery()
                .addBeanClasses(DIRAC_BEANS).addBeanClass(GaugeB.class)
                .initialize();
    }

    @AfterAll
    static void stop() {
        if (second != null) {
            second.close();
        }
        if (first != null) {
            first.close();
        }
    }

    @Test
    void eachContainerRegistersItsOwnGauges() {
        assertEquals(Set.of("first.gauge"), applicationGauges(first));
        assertEquals(Set.of("second.gauge"), applicationGauges(second));
    }

    private static Set<String> applicationGauges(WeldContainer container) {
        MetricRegistry registry = container.select(MetricRegistryProducerBean.class).get().registry(MetricRegistry.APPLICATION_SCOPE);
        return registry.getGauges().keySet().stream().map(id -> id.getName()).collect(Collectors.toSet());
    }
}
