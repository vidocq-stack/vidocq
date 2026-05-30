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
 * Knock Build Compatible Extension — validates the presence of a probe qualifier
 * on every CDI bean implementing {@link HealthCheck}.
 *
 * <p>MicroProfile Health 4.0 §4.2: "Health check procedures that do not carry one
 * of the three qualifiers result in a deployment error."</p>
 *
 * <p>This BCE only validates — registration in the registry is delegated to
 * {@link HealthCheckRegistrar} via standard CDI injection.</p>
 *
 * <p>Discovered via ServiceLoader:
 * {@code META-INF/services/jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension}
 * and {@code provides ... with} in {@code module-info.java}.</p>
 */
public class HealthCheckCdiExtension implements BuildCompatibleExtension {

    private static final String LIVENESS  = Liveness.class.getName();
    private static final String READINESS = Readiness.class.getName();
    private static final String STARTUP   = Startup.class.getName();

    /**
     * Spec §4.2: validates that every {@link HealthCheck} bean carries at least one of the
     * three probe qualifiers. Reports a deployment error otherwise.
     *
     * @param bean     the CDI bean to validate
     * @param messages deployment error collector
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
                    "Knock CDI: the HealthCheck bean '" + className
                    + "' has no @Liveness, @Readiness, or @Startup qualifier"
                    + " (MicroProfile Health 4.0 spec §4.2)",
                    bean);
        }
    }
}