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

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.TreeSet;

/**
 * How a jlink image, or the jpackage application built on it, starts the application: the
 * {@code <module>/<class>} its launcher runs, and the JVM options the image needs for it.
 *
 * <p>The launcher always runs the runtime's own main. {@value VidocqAppLayer#APP_MODULE_PROPERTY} names the
 * application module and {@value VidocqAppLayer#APP_MODULES_PROPERTY} the modules of the application layer, which
 * {@code Vidocq.main} reads from the image ({@code jrt:}); an application main class, a {@code @VidocqMain}
 * trampoline, is named by {@value VidocqAppLayer#APP_MAIN_PROPERTY} and runs inside that layer. A launcher running
 * the trampoline itself would resolve the application module into the boot layer, and the layer would hold a second
 * copy of it (BUG-20261114-11).
 *
 * <p>The build splits the staged modules as {@code vidocq:package} splits its jars. {@code --add-modules} roots the
 * parent layer and the JDK modules the image needs, never a module of the application layer. Every module of the
 * application layer is listed, so that one that nothing requires (a resource-only module) is resolved by the layer
 * (BUG-20261114-05).
 *
 * <p>The image bakes {@code -Dvidocq.app.layer=} with the mode of the build ({@code all} unless the build asked for
 * {@code classes}), so that it carries the mode its split was made under, and never leaves it to a default that a
 * later runtime could change (Vidocq/vidocq#247).
 *
 * @param entryPoint   the {@code <module>/<class>} the launcher runs: the runtime's main
 * @param vmOptions    the JVM options the image needs: the modules to resolve at start, the application module and
 *                     the modules of its layer, the application main class, and the mode of the application layer
 * @param layerModules the modules of the application layer, the application module first
 */
record ImageLaunch(String entryPoint, List<String> vmOptions, List<String> layerModules) {

    /** The runtime's own module, which holds {@link ApplicationMainClass#RUNTIME_MAIN_CLASS}. */
    static final String RUNTIME_MODULE = "io.vidocq.runtime.core";
    /**
     * The key of the image's {@code release} file that lists the modules of the application layer, comma separated:
     * {@code vidocq:jpackage} reads it to start the image as {@code vidocq:jlink} baked it.
     */
    static final String RELEASE_KEY = "VIDOCQ_APP_MODULES";

    ImageLaunch {
        vmOptions = List.copyOf(vmOptions);
        layerModules = List.copyOf(layerModules);
    }

    /**
     * The launch of the application whose module is {@code mainModule}, alone in the application layer, with nothing
     * else rooted.
     *
     * @param mainModule the application's own module ({@code vidocq.mainModule})
     * @param mainClass  its main class ({@code vidocq.mainClass}), or {@code null}, blank or the runtime's main
     *                   when it has none
     * @param layerMode  the mode of the application layer the image starts under ({@code vidocq.app.layer})
     * @throws IllegalArgumentException when {@code mainModule} is missing, or names the runtime
     */
    static ImageLaunch of(String mainModule, String mainClass, LayerSplit.Mode layerMode) {
        return of(mainModule, mainClass, layerMode, List.of(), List.of());
    }

    /**
     * The launch of the application whose module is {@code mainModule}.
     *
     * @param bootRoots    the modules the boot layer roots: the parent layer and the JDK modules the image needs; a
     *                     module of {@code layerModules} among them, and {@code java.base}, are left out
     * @param layerModules the modules of the application layer, which the image reads from {@code jrt:} at start; the
     *                     application module is added first when the list lacks it
     * @throws IllegalArgumentException when {@code mainModule} is missing, or names the runtime
     */
    static ImageLaunch of(String mainModule, String mainClass, LayerSplit.Mode layerMode,
                          Collection<String> bootRoots, Collection<String> layerModules) {
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
        var layer = new LinkedHashSet<String>();
        layer.add(module);
        layer.addAll(new TreeSet<>(layerModules));
        var roots = new TreeSet<>(bootRoots);
        roots.removeAll(layer);
        roots.remove("java.base");
        List<String> options = new java.util.ArrayList<>();
        if (!roots.isEmpty()) {
            options.add("--add-modules=" + String.join(",", roots));
        }
        options.add("-D" + VidocqAppLayer.APP_MODULE_PROPERTY + "=" + module);
        options.add("-D" + VidocqAppLayer.APP_MODULES_PROPERTY + "=" + String.join(",", layer));
        if (application != null) {
            options.add("-D" + VidocqAppLayer.APP_MAIN_PROPERTY + "=" + application);
        }
        options.add(layerOption(layerMode));
        return new ImageLaunch(RUNTIME_MODULE + "/" + ApplicationMainClass.RUNTIME_MAIN_CLASS, options,
                List.copyOf(layer));
    }

    private static String layerOption(LayerSplit.Mode layerMode) {
        return "-D" + LayerSplit.MODE_PROPERTY + "=" + layerMode.name().toLowerCase(java.util.Locale.ROOT);
    }
}
