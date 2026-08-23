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

import io.vidocq.runtime.spi.VidocqApp;

/**
 * Entry points to the Vidocq server.
 *
 * <p><b>Runtime-first launch</b> (packaged distributions, dev mode, plain server):
 *
 * <pre>{@code
 * java -m io.vidocq.runtime.core/io.vidocq.runtime.core.Vidocq
 * }</pre>
 *
 * <p><b>Application trampoline</b> — the official IDE-compatible entry point
 * ({@code @VidocqMain}): a right-click → Run on the application's main works with no
 * special configuration, because {@link #run} re-resolves the application (already on
 * the JVM module path) into a child module layer defined by the Vauban class loader —
 * classes are transformed at definition, no instrumentation agent:
 *
 * <pre>{@code
 * @VidocqMain
 * public final class App {
 *     public static void main(String[] args) {
 *         Vidocq.run(args);            // no business logic before this line
 *     }
 * }
 * }</pre>
 */
public final class Vidocq {

    private static final System.Logger LOG = System.getLogger(Vidocq.class.getName());

    /** The bootstrap of the current deployment — target of {@link #waitForExit()}. */
    private static volatile VidocqBootstrap current;

    private Vidocq() {}

    public static void main(String[] args) {
        // Dev-mode hot reload (-Dvidocq.dev.reload.file=…): boot/stop cycles over a
        // re-created application layer, in this same JVM.
        String reloadFile = System.getProperty(VidocqDevReloadLoop.RELOAD_FILE_PROPERTY, "").strip();
        if (!reloadFile.isEmpty()) {
            VidocqDevReloadLoop.run(java.nio.file.Path.of(reloadFile), args);
            return;
        }
        // Universal-loader mode (-Dvidocq.app.path=…): the application archives resolve
        // into a child module layer defined by the Vauban class loader. Must precede
        // everything — extensions and configuration read through the loader installed here.
        boolean layerJustInstalled = VidocqAppLayer.installIfConfigured();
        if (layerJustInstalled && VidocqAppLayer.runAppMainIfConfigured(args)) {
            // The application main (inside the layer) took over; when it calls
            // Vidocq.main/run back, the layer install is a no-op and the boot proceeds.
            return;
        }
        bootAndRegister().awaitShutdown();
    }

    /**
     * Boots the runtime from a {@code @VidocqMain} trampoline and blocks until shutdown.
     * See {@link #run(Class, String...)} for the application-callback variant.
     */
    public static void run(String... args) {
        var caller = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
                .getCallerClass();
        ensureLayer(caller);
        String reloadFile = System.getProperty(VidocqDevReloadLoop.RELOAD_FILE_PROPERTY, "").strip();
        if (!reloadFile.isEmpty()) {
            VidocqDevReloadLoop.run(java.nio.file.Path.of(reloadFile), args);
            return;
        }
        bootAndRegister().awaitShutdown();
    }

    /**
     * Boots the runtime from a {@code @VidocqMain} trampoline, then executes
     * {@code appClass} <em>inside the application layer</em>: the class is re-loaded
     * through the layer loader and instantiated there (as a CDI bean when it is one,
     * through its public no-arg constructor otherwise). When {@link VidocqApp#run}
     * returns, the runtime shuts down and its value is returned — a server application
     * blocks with {@link #waitForExit()}.
     */
    public static int run(Class<? extends VidocqApp> appClass, String... args) {
        var caller = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
                .getCallerClass();
        ensureLayer(caller);
        var bootstrap = bootAndRegister();
        try {
            VidocqApp app = instantiateInLayer(appClass);
            int code = app.run(args);
            bootstrap.shutdown();
            return code;
        } catch (Exception e) {
            bootstrap.shutdown();
            if (e instanceof RuntimeException re) throw re;
            throw new IllegalStateException("Application " + appClass.getName() + " failed", e);
        }
    }

    /** Blocks until the current deployment shuts down (Ctrl+C, SIGTERM, programmatic). */
    public static void waitForExit() {
        var bootstrap = current;
        if (bootstrap != null) {
            bootstrap.awaitShutdown();
        }
    }

    private static void ensureLayer(Class<?> caller) {
        if (VidocqAppLayer.alreadyInLayer()) {
            return;
        }
        if (VidocqAppLayer.installIfConfigured()) {          // explicit -Dvidocq.app.path wins
            return;
        }
        if (VidocqAppLayer.installFromBootLayer(caller)) {   // the IDE right-click path
            return;
        }
        LOG.log(System.Logger.Level.INFO, () -> "No Vauban application layer could be"
                + " created (caller " + caller.getName() + " is not in a named module"
                + " with detectable application archives) — booting directly; unwoven"
                + " beans fall back to the load-time weaving agent");
    }

    private static VidocqBootstrap bootAndRegister() {
        var bootstrap = VidocqBootstrap.create().configure().start();
        current = bootstrap;
        return bootstrap;
    }

    /** Re-loads {@code appClass} through the layer and instantiates it there. */
    private static VidocqApp instantiateInLayer(Class<? extends VidocqApp> appClass)
            throws Exception {
        var tccl = Thread.currentThread().getContextClassLoader();
        Class<?> layerClass = Class.forName(appClass.getName(), true, tccl);
        // As a CDI bean when the container knows it…
        try {
            var instance = jakarta.enterprise.inject.spi.CDI.current().select(layerClass);
            if (instance.isResolvable()) {
                return (VidocqApp) instance.get();
            }
        } catch (RuntimeException notABeanOrNoContainer) {
            // fall through to plain instantiation
        }
        // …otherwise through its public no-arg constructor (package exported to us
        // through the layer controller — the JDK-launcher technique).
        VidocqAppLayer.exportToRuntime(layerClass);
        return (VidocqApp) layerClass.getDeclaredConstructor().newInstance();
    }
}
