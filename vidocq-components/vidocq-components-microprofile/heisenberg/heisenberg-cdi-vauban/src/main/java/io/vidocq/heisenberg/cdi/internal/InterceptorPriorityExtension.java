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
package io.vidocq.heisenberg.cdi.internal;

import io.vidocq.heisenberg.internal.ConfigResolver;
import io.vidocq.vauban.api.ExtensionPhase;
import jakarta.annotation.Priority;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.ClassConfig;
import jakarta.enterprise.inject.build.compatible.spi.Enhancement;
import jakarta.enterprise.util.AnnotationLiteral;

/**
 * Build Compatible Extension that gives the {@link FaultToleranceInterceptor} the priority the configuration asks
 * for: MicroProfile Fault Tolerance 4.1 lets {@code mp.fault.tolerance.interceptor.priority} replace the default
 * priority, {@link ConfigResolver#DEFAULT_INTERCEPTOR_PRIORITY} (4010), and has it read once, when the application
 * starts.
 *
 * <p><strong>It runs when the application starts, never when it builds.</strong> The value belongs to the
 * configuration the application starts with: the system properties and the environment of its JVM, the properties
 * files of the deployment. A build reads the configuration of the build machine. Vauban runs the build compatible
 * extensions while the application compiles and freezes what they produce; with Vidocq/vauban#288 it runs an
 * extension again when the container starts only if that extension asked {@link ExtensionPhase#isBuildTime()}
 * during the build. So {@link #configureInterceptorPriority} asks first, whatever the configuration says, does
 * nothing at build time, and replaces the priority at start.
 *
 * <p>It is an extension of its own, apart from {@link HeisenbergExtension}, so that it alone runs again at start.
 * Binding the interceptor to the guarded classes and validating their definitions depend on the classes only: that
 * work of {@link HeisenbergExtension} stays frozen by the build.
 *
 * <p>Like every build compatible extension of this module it is declared twice: in
 * {@code META-INF/services} for the class path, and with {@code provides} in {@code module-info.java} for the
 * module path. Weld, Open Liberty and the other containers run it when they deploy the application.
 */
public class InterceptorPriorityExtension implements BuildCompatibleExtension {

    /**
     * Replaces the {@code @Priority} of the Fault Tolerance interceptor by the configured one. The default priority
     * is the one the class declares: nothing is asked then.
     */
    @Enhancement(types = FaultToleranceInterceptor.class)
    public void configureInterceptorPriority(ClassConfig classConfig) {
        if (runsAtBuildTime()) {
            return; // the configuration of the build machine is not the one the application starts with
        }
        int priority = ConfigResolver.interceptorPriority();
        if (priority == ConfigResolver.DEFAULT_INTERCEPTOR_PRIORITY) {
            return; // default priority already declared statically
        }
        classConfig.removeAnnotation(a -> Priority.class.getName().equals(a.name()));
        classConfig.addAnnotation(new PriorityLiteral(priority));
    }

    /**
     * Whether Vauban runs this extension while the application compiles. Asking is also what makes Vauban run the
     * extension again when the container starts.
     *
     * <p>{@code vauban-api} ships with this module, but a deployment on another container may resolve a version of
     * it older than {@code ExtensionPhase}: no Vauban build runs the extension there, the container does, when it
     * deploys the application.
     */
    private static boolean runsAtBuildTime() {
        try {
            return ExtensionPhase.isBuildTime();
        } catch (NoClassDefFoundError _) {
            return false;
        }
    }

    /**
     * Portable implementation of {@link Priority} (CDI {@link AnnotationLiteral} pattern). No {@code equals} of its
     * own: the one of {@link AnnotationLiteral} compares the members of the annotation, {@link #value()} included.
     */
    @SuppressWarnings("java:S2160")
    private static final class PriorityLiteral extends AnnotationLiteral<Priority> implements Priority {
        private static final long serialVersionUID = 1L;
        private final int value;

        PriorityLiteral(int value) {
            this.value = value;
        }

        @Override
        public int value() {
            return value;
        }
    }
}
