/*
 * Copyright (c) 2026 Vidocq contributors. Apache License 2.0.
 */
package io.vidocq.ravel.cdi;

import io.vidocq.ravel.cdi.internal.RavelConfigPropertyResolver;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.spi.InjectionPoint;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;

/**
 * Producers CDI legacy : conserve {@code @Produces Config} et un point d'entrée
 * unitaire {@link #produceConfigProperty(InjectionPoint)} pour les tests qui
 * appellent la résolution {@code @ConfigProperty} sans démarrer un container CDI.
 *
 * <p>En production CDI le résolveur de {@code @ConfigProperty} passe désormais
 * par les <em>synthetic beans</em> enregistrés par {@link ConfigCdiExtension}
 * (phase {@code @Synthesis}) qui délèguent à {@link RavelConfigPropertyResolver}.
 * Le {@code @Produces @ConfigProperty Object} historique a été retiré pour
 * éviter toute ambiguïté de résolution Weld.</p>
 */
@Dependent
public class RavelConfigProducer {

    @Produces
    @Dependent
    public Config produceConfig() {
        return ConfigProvider.getConfig();
    }

    /**
     * Point d'entrée non-CDI utilisé par les tests unitaires pour valider la
     * logique de résolution. Délègue à {@link RavelConfigPropertyResolver}.
     */
    public Object produceConfigProperty(InjectionPoint injectionPoint) {
        return RavelConfigPropertyResolver.resolve(injectionPoint);
    }
}
