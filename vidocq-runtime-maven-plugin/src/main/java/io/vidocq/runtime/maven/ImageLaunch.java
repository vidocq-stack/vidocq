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
package io.vidocq.runtime.maven;

import io.vidocq.runtime.core.VidocqAppLayer;

import java.util.List;

/**
 * How a jlink image, or the jpackage application built on it, starts the application: the
 * {@code <module>/<class>} its launcher runs, and the JVM options the image needs for it.
 *
 * <p>With an application main class, the launcher runs it as it is: the {@code @VidocqMain} trampoline
 * re-layers the application. Without one, the launcher runs the runtime's own main, and two options do what
 * the trampoline did: {@code --add-modules} resolves the application module, which no module of the runtime
 * reads, into the boot layer, and {@value VidocqAppLayer#APP_MODULE_PROPERTY} tells {@code Vidocq.main} to
 * re-layer it. The application then boots in the same layer as with a trampoline.
 *
 * @param entryPoint the {@code <module>/<class>} the launcher runs
 * @param vmOptions  the JVM options the image needs, empty with an application main class
 */
record ImageLaunch(String entryPoint, List<String> vmOptions) {

    /** The runtime's own module, which holds {@link ApplicationMainClass#RUNTIME_MAIN_CLASS}. */
    static final String RUNTIME_MODULE = "io.vidocq.runtime.core";

    ImageLaunch {
        vmOptions = List.copyOf(vmOptions);
    }

    /**
     * The launch of the application whose module is {@code mainModule}.
     *
     * @param mainModule the application's own module ({@code vidocq.mainModule})
     * @param mainClass  its main class ({@code vidocq.mainClass}), or {@code null}, blank or the runtime's main
     *                   when it has none
     * @throws IllegalArgumentException when {@code mainModule} is missing, or names the runtime without a main
     *                                  class of the application
     */
    static ImageLaunch of(String mainModule, String mainClass) {
        if (mainModule == null || mainModule.isBlank()) {
            throw new IllegalArgumentException("The image needs the application's module: set vidocq.mainModule"
                    + " (or <mainModule>) to the name its module-info.java declares.");
        }
        String module = mainModule.strip();
        String application = ApplicationMainClass.normalize(mainClass == null ? null : mainClass.strip());
        if (application != null) {
            return new ImageLaunch(module + "/" + application, List.of());
        }
        if (RUNTIME_MODULE.equals(module)) {
            throw new IllegalArgumentException("vidocq.mainModule is the runtime's module " + RUNTIME_MODULE
                    + ": set it to the application's own module, the runtime's main then starts it.");
        }
        return new ImageLaunch(RUNTIME_MODULE + "/" + ApplicationMainClass.RUNTIME_MAIN_CLASS,
                List.of("--add-modules=" + module, "-D" + VidocqAppLayer.APP_MODULE_PROPERTY + "=" + module));
    }
}
