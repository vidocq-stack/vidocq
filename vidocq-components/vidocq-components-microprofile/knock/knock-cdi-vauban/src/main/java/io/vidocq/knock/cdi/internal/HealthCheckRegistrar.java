/*
 * Copyright (c) 2026 Vidocq contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package io.vidocq.knock.cdi.internal;

import io.vidocq.knock.spi.HealthCheckRegistry;
import io.vidocq.knock.spi.ProbeType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.Liveness;
import org.eclipse.microprofile.health.Readiness;
import org.eclipse.microprofile.health.Startup;

/**
 * Enregistre automatiquement les beans CDI {@link HealthCheck} dans le
 * {@link HealthCheckRegistry} au démarrage du contexte applicatif.
 *
 * <p>Spec MicroProfile Health 4.0 §4.1 : « Health check procedures that implement
 * the HealthCheck interface and are annotated with one of the three qualifiers are
 * automatically discovered and registered. »</p>
 *
 * <p>L'enregistrement est déclenché par l'événement
 * {@code @Initialized(ApplicationScoped.class)}, qui est levé au démarrage du
 * contexte applicatif CDI — avant toute requête HTTP. Cela garantit que le registry
 * est peuplé avant que les endpoints {@code /health*} reçoivent du trafic.</p>
 *
 * <p>Nom de clé dans le registry : {@code check.getClass().getName()}.
 * Pour les beans proxyfiés (sous-classes dynamiques Vauban), le nom inclut le suffixe
 * de proxy — ce suffixe est déterministe et unique par classe de bean, donc
 * la déduplication fonctionne correctement.</p>
 */
@ApplicationScoped
class HealthCheckRegistrar {

    @Inject
    private HealthCheckRegistry registry;

    @Inject @Liveness
    private Instance<HealthCheck> livenessChecks;

    @Inject @Readiness
    private Instance<HealthCheck> readinessChecks;

    @Inject @Startup
    private Instance<HealthCheck> startupChecks;

    /**
     * Enregistre tous les checks découverts au démarrage du contexte applicatif.
     *
     * <p>Spec §4.1 : les beans qualifiés sont enregistrés par type de probe.</p>
     *
     * @param ignored l'événement CDI {@code @Initialized(ApplicationScoped.class)}
     */
    void onApplicationStart(@Observes @Initialized(ApplicationScoped.class) Object ignored) {
        livenessChecks.forEach(c  -> registry.register(ProbeType.LIVENESS,  c.getClass().getName(), c));
        readinessChecks.forEach(c -> registry.register(ProbeType.READINESS, c.getClass().getName(), c));
        startupChecks.forEach(c   -> registry.register(ProbeType.STARTUP,   c.getClass().getName(), c));
    }
}