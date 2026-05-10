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

import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.Messages;
import jakarta.enterprise.inject.build.compatible.spi.Registration;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.Liveness;
import org.eclipse.microprofile.health.Readiness;
import org.eclipse.microprofile.health.Startup;

import jakarta.enterprise.inject.build.compatible.spi.BeanInfo;

/**
 * Build Compatible Extension Knock — validation de la présence d'un qualifieur de probe
 * sur tout bean CDI implémentant {@link HealthCheck}.
 *
 * <p>Spec MicroProfile Health 4.0 §4.2 : « Health check procedures that do not carry one
 * of the three qualifiers result in a deployment error. »</p>
 *
 * <p>Cette BCE ne fait que valider — l'enregistrement dans le registry est délégué
 * à {@link HealthCheckRegistrar} via injection CDI standard.</p>
 *
 * <p>Découverte via ServiceLoader :
 * {@code META-INF/services/jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension}
 * et {@code provides ... with} dans {@code module-info.java}.</p>
 */
public class HealthCheckCdiExtension implements BuildCompatibleExtension {

    private static final String LIVENESS  = Liveness.class.getName();
    private static final String READINESS = Readiness.class.getName();
    private static final String STARTUP   = Startup.class.getName();

    /**
     * Spec §4.2 : valide que tout bean {@link HealthCheck} porte au moins un des trois
     * qualifieurs de probe. Signale une erreur de déploiement sinon.
     *
     * @param bean     le bean CDI à valider
     * @param messages collecteur d'erreurs de déploiement
     */
    @Registration(types = HealthCheck.class)
    public void validateProbeQualifier(BeanInfo bean, Messages messages) {
        boolean hasProbe = bean.qualifiers().stream()
                .anyMatch(q -> LIVENESS.equals(q.name())
                            || READINESS.equals(q.name())
                            || STARTUP.equals(q.name()));

        if (!hasProbe) {
            ClassInfo declaring = bean.declaringClass();
            String className = declaring != null ? declaring.name() : "(unknown)";
            messages.error(
                    "Knock CDI : le bean HealthCheck '" + className
                    + "' n'a aucun qualifieur @Liveness, @Readiness ou @Startup"
                    + " (spec MicroProfile Health 4.0 §4.2)",
                    bean);
        }
    }
}