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
     * Producer qualifié pour les tests TCK qui injectent
     * {@code @Inject @RegistryType(type=Type.BASE) MetricRegistryProxy} (cf.
     * {@code AllMetricsTest.testMetricUnits}).
     *
     * <p>En MP Metrics 5, la distinction BASE/APPLICATION/VENDOR est dépréciée — toutes
     * les métriques cohabitent dans le même registre. On expose donc le même registre
     * Dirac sous le qualificateur attendu par le TCK.</p>
     */
    @SuppressWarnings("deprecation")
    @Produces
    @RegistryType(type = MetricRegistry.Type.BASE)
    public MetricRegistryProxy produceBase() {
        return buildProxy();
    }

    /**
     * Producer de {@link MetricRegistry} qualifié {@code @RegistryType(BASE)} — requis par
     * le bean {@code MetricRegistryProvider} du TCK qui fait
     * {@code CDI.current().select(MetricRegistry.class, RegistryTypeLiteral.BASE)} pour
     * construire son propre {@code MetricRegistryProxy}. Dirac n'expose que le registre
     * {@code @RegistryType()} (par défaut Type.APPLICATION) — on réexpose la même
     * instance avec le qualifier BASE attendu par la spec MP Metrics 4.x.
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
