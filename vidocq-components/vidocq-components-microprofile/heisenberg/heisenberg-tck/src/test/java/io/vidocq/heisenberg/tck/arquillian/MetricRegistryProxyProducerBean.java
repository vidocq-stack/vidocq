/*
 * Copyright (c) 2026 Vidocq contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
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
 * Produit un bean {@link MetricRegistryProxy} {@code @Default} qui enveloppe le registre
 * Dirac APPLICATION, requis par les tests TCK MicroProfile Fault Tolerance metrics.
 *
 * <p>Les métriques FT sont publiées dans le registre APPLICATION par {@code DiracFtMetricsRecorder}.
 * Ce producer expose ce registre via l'interface TCK {@code MetricRegistryProxy} avec
 * qualificateur {@code @Default} (les tests TCK injectent sans qualificateur).</p>
 */
@ApplicationScoped
class MetricRegistryProxyProducerBean {

    @SuppressWarnings("deprecation")
    @Inject
    @RegistryType
    private MetricRegistry applicationRegistry;

    @Produces
    MetricRegistryProxy produce() {
        return (MetricRegistryProxy) Proxy.newProxyInstance(
                MetricRegistryProxy.class.getClassLoader(),
                new Class<?>[] { MetricRegistryProxy.class },
                new MetricRegistryProxyHandler(applicationRegistry)
        );
    }
}
