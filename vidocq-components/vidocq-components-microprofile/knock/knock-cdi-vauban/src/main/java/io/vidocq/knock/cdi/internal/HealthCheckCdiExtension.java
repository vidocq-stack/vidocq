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