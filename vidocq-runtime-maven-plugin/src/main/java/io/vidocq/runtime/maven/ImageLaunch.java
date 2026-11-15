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

import io.vidocq.runtime.core.LayerSplit;
import io.vidocq.runtime.core.VidocqAppLayer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;

/**
 * How a jlink image, or the jpackage application built on it, starts the application: the
 * {@code <module>/<class>} its launcher runs, and the JVM options the image needs for it.
 *
 * <p>An image has one layer, the JVM's own (Vidocq/vidocq#580). Every module linked into an image is visible to its
 * boot layer, and the JVM binds there any module that provides a service a resolved module uses: an application
 * layer on top would hold a second copy of the application (BUG-20261114-11). So the image starts as a plain
 * modular application: {@value LayerSplit#OFF_PROPERTY} tells the runtime to build no layer, and the build has
 * written into the staged modules the {@code provides} the layer would have promoted at start
 * ({@link StagedProvides}).
 *
 * <p>The launcher runs the application's main class when it has one, a {@code @VidocqMain} trampoline, and the
 * runtime's own main otherwise; {@value VidocqAppLayer#APP_MODULE_PROPERTY} then names the application module for
 * the banner and the startup report.
 *
 * <p>{@code --add-modules} roots every module the build staged, and the JDK modules it added: a module that nothing
 * requires, such as a resource-only one, is resolved too (BUG-20261114-05).
 *
 * @param entryPoint the {@code <module>/<class>} the launcher runs
 * @param vmOptions  the JVM options the image needs: the modules to resolve at start, the switch that turns the
 *                   application layer off, and the application module when the runtime's main is launched
 */
record ImageLaunch(String entryPoint, List<String> vmOptions) {

    /** The runtime's own module, which holds {@link ApplicationMainClass#RUNTIME_MAIN_CLASS}. */
    static final String RUNTIME_MODULE = "io.vidocq.runtime.core";

    ImageLaunch {
        vmOptions = List.copyOf(vmOptions);
    }

    /**
     * The launch of the application whose module is {@code mainModule}, with nothing rooted.
     *
     * @param mainModule the application's own module ({@code vidocq.mainModule})
     * @param mainClass  its main class ({@code vidocq.mainClass}), or {@code null}, blank or the runtime's main
     *                   when it has none
     * @throws IllegalArgumentException when {@code mainModule} is missing, or names the runtime
     */
    static ImageLaunch of(String mainModule, String mainClass) {
        return of(mainModule, mainClass, List.of());
    }

    /**
     * The launch of the application whose module is {@code mainModule}.
     *
     * @param roots the modules the image resolves at start: those the build staged and the JDK modules it added;
     *              {@code java.base} and the module the launcher runs are left out
     * @throws IllegalArgumentException when {@code mainModule} is missing, or names the runtime
     */
    static ImageLaunch of(String mainModule, String mainClass, Collection<String> roots) {
        if (mainModule == null || mainModule.isBlank()) {
            throw new IllegalArgumentException("The image needs the application's module: set vidocq.mainModule"
                    + " (or <mainModule>) to the name its module-info.java declares.");
        }
        String module = mainModule.strip();
        if (RUNTIME_MODULE.equals(module)) {
            throw new IllegalArgumentException("vidocq.mainModule is the runtime's module " + RUNTIME_MODULE
                    + ": set it to the application's own module, the runtime's main then starts it.");
        }
        String application = ApplicationMainClass.normalize(mainClass == null ? null : mainClass.strip());
        String launched = application == null ? RUNTIME_MODULE : module;
        var rooted = new TreeSet<>(roots);
        rooted.remove("java.base");
        rooted.remove(launched);
        List<String> options = new ArrayList<>();
        if (!rooted.isEmpty()) {
            options.add("--add-modules=" + String.join(",", rooted));
        }
        options.add("-D" + LayerSplit.OFF_PROPERTY + "=true");
        if (application == null) {
            options.add("-D" + VidocqAppLayer.APP_MODULE_PROPERTY + "=" + module);
            return new ImageLaunch(RUNTIME_MODULE + "/" + ApplicationMainClass.RUNTIME_MAIN_CLASS, options);
        }
        return new ImageLaunch(module + "/" + application, options);
    }
}
