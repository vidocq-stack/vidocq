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

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Connects the metrics discovered by the BCE to the registries targeted by their scope at CDI startup.
 * Pre-registers gauges, timers, and counters before any method call.
 */
@ApplicationScoped
public class GaugeRegistrationBean {
    @Inject
    MetricRegistryProducerBean registries;

    // This container's metrics, synthesised by DiracExtension (dirac#23). An Instance, not a plain
    // injection point: the synthetic bean exists only once the extension ran in this container.
    @Inject
    Instance<DiscoveredMetrics> discovered;

    // Gauge beans are looked up in this container, not through CDI.current(), which is ambiguous
    // when several containers run in one JVM.
    @Inject
    Instance<Object> beans;

    private final AtomicBoolean registered = new AtomicBoolean();

    @PostConstruct
    void registerMetrics() {
        doRegister();
    }

    void onApplicationStart(@Observes @Initialized(ApplicationScoped.class) Object ignored) {
        doRegister();
    }

    private void doRegister() {
        if (!registered.compareAndSet(false, true)) {
            return;
        }
        if (discovered.isUnsatisfied()) {
            return;
        }
        DiscoveredMetrics metrics = discovered.get();
        metrics.registerGauges(registries, beanClass -> beans.select(beanClass).get());
        metrics.registerTimers(registries);
        metrics.registerCounters(registries);
    }
}
