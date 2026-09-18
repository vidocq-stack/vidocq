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
package io.vidocq.runtime.core;

import io.vidocq.vauban.classloader.VaubanLayerFactory;
import io.vidocq.vauban.classloader.spi.PluginContext;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Universal-loader launch mode (Vauban class-loader study, M2): when
 * {@value #APP_PATH_PROPERTY} is set, the application archives are NOT on the JVM module
 * path — the runtime resolves them into a child module layer whose defining loader is the
 * {@code VaubanClassLoader}, so every application class flows through the source and
 * transformer plugins (sjar decryption, cdi-proxifier weaving) at definition. Exports and
 * opens keep being enforced inside the child layer.
 *
 * <p>Must run before anything else touches application classes — it is the first call of
 * {@link Vidocq#main}; extensions, configuration sources and the container all resolve
 * application classes and resources through the context class loader installed here.
 *
 * <pre>{@code
 * java -p <runtime modules> --add-modules ALL-MODULE-PATH \
 *      -Dvidocq.app.path=app/target/classes:libs/extra.jar \
 *      -m io.vidocq.runtime.core/io.vidocq.runtime.core.Vidocq
 * }</pre>
 *
 * <p>An application {@code main} class (inside the layer) can be named with
 * {@value #APP_MAIN_PROPERTY}: {@link Vidocq#main} then delegates to it after installing
 * the layer — and when that main calls {@code Vidocq.main} back (the usual pattern), the
 * second {@link #installIfConfigured()} is a no-op and the regular boot proceeds.
 */
public final class VidocqAppLayer {

    /** {@code File.pathSeparator}-separated application archives (jars, dirs, sjars). */
    public static final String APP_PATH_PROPERTY = "vidocq.app.path";
    /** Binary name of an application main class to run once the layer is installed. */
    public static final String APP_MAIN_PROPERTY = "vidocq.app.main";

    private static final System.Logger LOG = System.getLogger(VidocqAppLayer.class.getName());

    /** The layer installed by this JVM, kept for {@link #runAppMainIfConfigured}. */
    private static volatile VaubanLayerFactory.AppLayer installed;
    /** The context loader that was active before the layer install (reload restores it). */
    private static volatile ClassLoader parentLoaderBeforeInstall;
    /** How the installed layer was found, for the startup report; {@code null} without a layer. */
    private static volatile Installation installation;

    /**
     * How the application layer was found, as the startup report prints it: strings only, so that the
     * report keeps nothing of a layer the dev reload tears down.
     *
     * @param origin   {@value #APP_PATH_PROPERTY}, or {@code boot-layer detection from <module>}
     * @param archives the archives resolved into the layer, as they were given
     * @param listed   whether {@code origin} is the property that lists {@code archives}
     */
    record Installation(String origin, List<String> archives, boolean listed) {

        Installation {
            archives = List.copyOf(archives);
        }
    }

    private VidocqAppLayer() {}

    /**
     * Tears the current application layer down so the next {@link #installIfConfigured()}
     * resolves a fresh one over the (recompiled) archives — the dev-mode hot reload.
     * The old layer's classes become collectable once the new container drops the last
     * reference; its archive readers are closed here.
     */
    static void resetForReload() {
        var layer = installed;
        if (layer == null) {
            return;
        }
        installed = null;
        installation = null;
        Thread.currentThread().setContextClassLoader(parentLoaderBeforeInstall);
        try {
            layer.loader().close();
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG, "Closing the old layer loader failed: " + e);
        }
    }

    /**
     * Creates the application layer and installs its loader as the context class loader
     * when {@value #APP_PATH_PROPERTY} is set. Returns {@code true} when the layer was
     * installed by THIS call — {@code false} when the property is absent or the layer is
     * already in place (a {@code VaubanClassLoader} already sits in the context-loader
     * chain), which is what makes application mains calling {@code Vidocq.main} back
     * re-entrant.
     */
    public static boolean installIfConfigured() {
        var property = System.getProperty(APP_PATH_PROPERTY, "").strip();
        if (property.isEmpty()) {
            return false;
        }
        if (alreadyInLayer()) {
            return false;
        }
        List<Path> paths = Arrays.stream(property.split(File.pathSeparator))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .map(Path::of)
                .toList();
        var parentLayer = VidocqAppLayer.class.getModule().getLayer();
        if (parentLayer == null) {
            // class-path launch — the runtime lives in the unnamed module
            parentLayer = ModuleLayer.boot();
        }
        return installLayer(paths, parentLayer, APP_PATH_PROPERTY + "=" + property,
                new Installation(APP_PATH_PROPERTY, paths.stream().map(Path::toString).toList(), true));
    }

    /**
     * The trampoline path ({@code Vidocq.run()} from a plain IDE launch): the caller's
     * module was resolved into the boot layer together with the rest of the application.
     * This re-resolves the application archives, chosen by {@link #applicationPaths}, into a
     * fresh Vauban layer. Module names deliberately shadow their boot-layer twins.
     *
     * <p>{@code -Dvidocq.app.modules=<name,name>} overrides the detection with an
     * explicit module list.
     */
    static boolean installFromBootLayer(Class<?> caller) {
        if (alreadyInLayer()) {
            return false;
        }
        var callerModule = caller.getModule();
        var callerLayer = callerModule.getLayer();
        if (!callerModule.isNamed() || callerLayer == null) {
            return false; // class-path launch — nothing to re-layer, agent net applies
        }
        var explicit = System.getProperty("vidocq.app.modules", "").strip();
        var configuration = callerLayer.configuration();
        var paths = explicit.isEmpty()
                ? applicationPaths(configuration, callerModule.getName())
                : explicitPaths(configuration, java.util.Set.of(explicit.split("\\s*,\\s*")));
        if (paths.isEmpty()) {
            return false;
        }
        String origin = "boot-layer detection from " + callerModule.getName();
        return installLayer(paths, callerLayer, origin,
                new Installation(origin, paths.stream().map(Path::toString).toList(), false));
    }

    /** The {@code file:} locations of the modules named by {@code -Dvidocq.app.modules}. */
    private static List<Path> explicitPaths(java.lang.module.Configuration configuration,
            java.util.Set<String> names) {
        var paths = new java.util.LinkedHashSet<Path>();
        for (var resolved : configuration.modules()) {
            if (names.contains(resolved.name())) {
                resolved.reference().location()
                        .filter(uri -> "file".equals(uri.getScheme()))
                        .ifPresent(uri -> paths.add(Path.of(uri)));
            }
        }
        return List.copyOf(paths);
    }

    /**
     * The {@code file:} locations of the modules of {@code configuration} to re-layer for an
     * application whose trampoline lives in {@code callerModule}: Vauban's re-layer policy, the
     * one of its Java SE launcher, with the Vidocq bricks kept as well and the caller as a root.
     * A CDI-agnostic library that only the application reads moves with it, so the Vauban loader
     * can place the client proxies that must live in its packages (vauban#53).
     */
    static List<Path> applicationPaths(java.lang.module.Configuration configuration,
            String callerModule) {
        return VaubanLayerFactory.applicationPaths(configuration, RUNTIME_MODULE_PREFIXES,
                java.util.Set.of(callerModule));
    }

    private static boolean installLayer(List<Path> paths, ModuleLayer parentLayer, String origin,
            Installation found) {
        try {
            var parentLoader = Thread.currentThread().getContextClassLoader();
            var appLayer = VaubanLayerFactory.createAppLayer(paths, parentLayer,
                    parentLoader, pluginContext());
            parentLoaderBeforeInstall = parentLoader;
            Thread.currentThread().setContextClassLoader(appLayer.loader());
            installed = appLayer;
            installation = found;
            LOG.log(System.Logger.Level.INFO, "Application layer ready: modules {0} ({1})",
                    appLayer.moduleNames(), origin);
            return true;
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Cannot create the application layer (" + origin + ")", e);
        }
    }

    /** The application layer installed by this JVM, or {@code null}. */
    static ModuleLayer installedLayer() {
        var layer = installed;
        return layer == null ? null : layer.layer();
    }

    /** How the installed application layer was found, or {@code null} when there is none. */
    static Installation installation() {
        return installed == null ? null : installation;
    }

    static boolean alreadyInLayer() {
        for (var l = Thread.currentThread().getContextClassLoader(); l != null; l = l.getParent()) {
            if (l instanceof io.vidocq.vauban.classloader.VaubanClassLoader) {
                return true;
            }
        }
        return false;
    }

    /**
     * Exports the class' package to the runtime module through the layer controller —
     * the JDK-launcher technique, needed before any reflective touch of an application
     * type (packages are fully encapsulated).
     */
    static void exportToRuntime(Class<?> layerClass) {
        var layer = installed;
        if (layer != null && layerClass.getModule().getLayer() == layer.layer()) {
            layer.controller().addExports(layerClass.getModule(),
                    layerClass.getPackageName(), VidocqAppLayer.class.getModule());
        }
    }

    /**
     * Opens the class' package to the runtime module, on top of {@link #exportToRuntime}. An
     * export is enough to reach a {@code public} member; a main that is not public — which
     * Java 25 accepts — additionally needs {@code setAccessible}, which a named module grants
     * only for an open package (vidocq#88).
     */
    static void openToRuntime(Class<?> layerClass) {
        var layer = installed;
        if (layer != null && layerClass.getModule().getLayer() == layer.layer()) {
            layer.controller().addOpens(layerClass.getModule(),
                    layerClass.getPackageName(), VidocqAppLayer.class.getModule());
        }
    }

    /** The Vidocq bricks: module-name prefixes kept in the boot layer on top of Vauban's own rules. */
    private static final List<String> RUNTIME_MODULE_PREFIXES = List.of(
            "java.", "jdk.", "jakarta.", "org.eclipse.",
            "io.vidocq.vauban", "io.vidocq.cassini", "io.vidocq.chappe",
            "io.vidocq.champollion", "io.vidocq.cyrano", "io.vidocq.cervantes",
            "io.vidocq.knock", "io.vidocq.dirac", "io.vidocq.heisenberg",
            "io.vidocq.grimm", "io.vidocq.ravel", "io.vidocq.humboldt",
            "io.vidocq.mansart",
            "io.vidocq.runtime.core", "io.vidocq.runtime.spi",
            "io.vidocq.runtime.extensions");

    /**
     * Invokes the {@value #APP_MAIN_PROPERTY} class' main method through the installed layer
     * loader, in any of the shapes the Java SE launcher accepts ({@link ApplicationMain}).
     * Returns {@code false} when no application main is configured.
     */
    static boolean runAppMainIfConfigured(String[] args) {
        var mainName = System.getProperty(APP_MAIN_PROPERTY, "").strip();
        if (mainName.isEmpty()) {
            return false;
        }
        try {
            var mainClass = Class.forName(mainName, false,
                    Thread.currentThread().getContextClassLoader());
            var main = ApplicationMain.of(mainClass);
            // The class named by the property is instantiated for an instance main, but the
            // method can be declared higher up, in another package of another module: both
            // sides have to be reachable (vidocq#88).
            for (var type : main.typesToOpen()) {
                exportToRuntime(type);
                openToRuntime(type);
            }
            main.invoke(args);
            return true;
        } catch (ReflectiveOperationException e) {
            var cause = e instanceof java.lang.reflect.InvocationTargetException ite
                    ? ite.getCause() : e;
            if (cause instanceof RuntimeException re) throw re;
            if (cause instanceof Error err) throw err;
            throw new IllegalStateException("Cannot run application main " + mainName, cause);
        }
    }

    /** The sjar key provider when vauban-sjar is present, otherwise an empty context. */
    private static PluginContext pluginContext() {
        try {
            var providerClass = Class.forName("io.vidocq.vauban.sjar.SjarKeyProvider");
            return (PluginContext) providerClass.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            return PluginContext.empty();
        }
    }
}
