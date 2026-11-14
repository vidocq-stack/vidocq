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
import java.util.LinkedHashSet;
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
 * <p>Both ways bake {@code -Dvidocq.app.layer=} with the mode of the build ({@code all} unless the build asked for
 * {@code classes}): the image re-layers at start, so it must carry the mode it was built under, and never leave it
 * to a default that a later runtime could change (Vidocq/vidocq#247).
 *
 * @param entryPoint the {@code <module>/<class>} the launcher runs
 * @param vmOptions  the JVM options the image needs: the modules to resolve at start, the mode of the application
 *                   layer, and, without an application main class, the application module
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
     * @param layerMode  the mode of the application layer the image starts under ({@code vidocq.app.layer})
     * @throws IllegalArgumentException when {@code mainModule} is missing, or names the runtime without a main
     *                                  class of the application
     */
    static ImageLaunch of(String mainModule, String mainClass, LayerSplit.Mode layerMode) {
        return of(mainModule, mainClass, layerMode, List.of());
    }

    /**
     * The launch of the application whose module is {@code mainModule}, with every module of the image resolved
     * at start. jlink links all the staged modules, but the JVM resolves only the roots and what they
     * {@code requires}: a module that nothing requires (a resource-only module such as Vaadin's {@code flow.push})
     * would be in the image and in no layer, its {@code META-INF/resources} unreachable (BUG-20261114-05). Both
     * launch shapes therefore bake {@code --add-modules=<mainModule>,<the other staged modules>}, as
     * {@code vidocq:package} does with {@code ALL-MODULE-PATH}.
     *
     * @param stagedModules the names of the modules staged in the image (the application's own is rooted first,
     *                      once, wherever the list holds it)
     */
    static ImageLaunch of(String mainModule, String mainClass, LayerSplit.Mode layerMode,
                          Collection<String> stagedModules) {
        if (mainModule == null || mainModule.isBlank()) {
            throw new IllegalArgumentException("The image needs the application's module: set vidocq.mainModule"
                    + " (or <mainModule>) to the name its module-info.java declares.");
        }
        String module = mainModule.strip();
        String application = ApplicationMainClass.normalize(mainClass == null ? null : mainClass.strip());
        String roots = rootOption(module, stagedModules);
        if (application != null) {
            return new ImageLaunch(module + "/" + application, List.of(roots, layerOption(layerMode)));
        }
        if (RUNTIME_MODULE.equals(module)) {
            throw new IllegalArgumentException("vidocq.mainModule is the runtime's module " + RUNTIME_MODULE
                    + ": set it to the application's own module, the runtime's main then starts it.");
        }
        return new ImageLaunch(RUNTIME_MODULE + "/" + ApplicationMainClass.RUNTIME_MAIN_CLASS,
                List.of(roots, "-D" + VidocqAppLayer.APP_MODULE_PROPERTY + "=" + module,
                        layerOption(layerMode)));
    }

    private static String rootOption(String mainModule, Collection<String> stagedModules) {
        var roots = new LinkedHashSet<String>();
        roots.add(mainModule);
        roots.addAll(stagedModules);
        return "--add-modules=" + String.join(",", new ArrayList<>(roots));
    }

    private static String layerOption(LayerSplit.Mode layerMode) {
        return "-D" + LayerSplit.MODE_PROPERTY + "=" + layerMode.name().toLowerCase(java.util.Locale.ROOT);
    }
}
