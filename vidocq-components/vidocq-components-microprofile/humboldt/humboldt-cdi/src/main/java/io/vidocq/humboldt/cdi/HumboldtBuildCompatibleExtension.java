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
package io.vidocq.humboldt.cdi;

import io.opentelemetry.instrumentation.annotations.WithSpan;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.ClassConfig;
import jakarta.enterprise.inject.build.compatible.spi.Enhancement;
import jakarta.enterprise.inject.build.compatible.spi.MethodConfig;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;

/**
 * CDI 4.x BuildCompatibleExtension compatible with both Lite and Full — detects at build
 * time the classes and methods annotated with
 * {@link io.opentelemetry.instrumentation.annotations.WithSpan @WithSpan}
 * and automatically adds the {@link SpanBinding} marker to them, which triggers
 * the activation of {@link WithSpanInterceptor} by the CDI container.
 *
 * <p>Result: the user only writes {@code @WithSpan} (the standardised OTel public API
 * annotation expected by the MicroProfile Telemetry 2.1 TCK). No double annotation.</p>
 *
 * <p>Discovery: via CDI ServiceLoader (entry in
 * {@code META-INF/services/jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension}
 * and JPMS {@code provides} binding). Must be called automatically by any conformant
 * CDI 4.x container (Vauban CDI Lite, Weld 5+, etc.).</p>
 */
public final class HumboldtBuildCompatibleExtension implements BuildCompatibleExtension {

    private static final Logger LOG = System.getLogger(HumboldtBuildCompatibleExtension.class.getName());

    /**
     * {@code @Enhancement} hook on every class (and its subtypes) carrying
     * {@code @WithSpan} anywhere (class or method).
     *
     * <p>Strategy:</p>
     * <ul>
     *   <li>If the class is annotated with {@code @WithSpan} → adds {@link SpanBinding} on the class
     *       (all methods become intercepted)</li>
     *   <li>Otherwise, iterates over methods: each method annotated with {@code @WithSpan}
     *       receives {@link SpanBinding}</li>
     * </ul>
     */
    @Enhancement(types = Object.class, withSubtypes = true, withAnnotations = WithSpan.class)
    public void addSpanBinding(ClassConfig classConfig) {
        boolean classLevel = classConfig.info().hasAnnotation(WithSpan.class);
        if (classLevel) {
            classConfig.addAnnotation(SpanBinding.class);
            LOG.log(Level.DEBUG, "Add @SpanBinding on class {0}", classConfig.info().name());
        }
        for (MethodConfig m : classConfig.methods()) {
            if (m.info().hasAnnotation(WithSpan.class)) {
                m.addAnnotation(SpanBinding.class);
                LOG.log(Level.DEBUG,
                        "Add @SpanBinding on {0}.{1}",
                        classConfig.info().name(), m.info().name());
            }
        }
    }
}
