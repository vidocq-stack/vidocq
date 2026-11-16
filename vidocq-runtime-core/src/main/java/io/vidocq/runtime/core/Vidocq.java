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

import io.vidocq.runtime.core.console.ConsoleLogging;
import io.vidocq.runtime.spi.VidocqApp;

import java.util.concurrent.atomic.AtomicReference;

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
    private static final AtomicReference<VidocqBootstrap> CURRENT = new AtomicReference<>();
    /** The module of the class that called {@link #run}: the application the banner names. */
    private static final AtomicReference<Module> APPLICATION_MODULE = new AtomicReference<>();

    private Vidocq() {}

    public static void main(String[] args) {
        // Before the first log line (VidocqAppLayer logs "Application layer ready").
        ConsoleLogging.installIfDefault();
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
        // Without a path, -Dvidocq.app.module=… names the application module the launch resolved into the boot
        // layer: re-layered as a trampoline would, unless the launch wants no layer (a jlink image).
        boolean layerJustInstalled = VidocqAppLayer.installIfConfigured()
                || VidocqAppLayer.installFromModuleIfConfigured();
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
        ConsoleLogging.installIfDefault();
        var caller = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
                .getCallerClass();
        APPLICATION_MODULE.set(caller.getModule());
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
        ConsoleLogging.installIfDefault();
        var caller = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
                .getCallerClass();
        APPLICATION_MODULE.set(caller.getModule());
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
        var bootstrap = CURRENT.get();
        if (bootstrap != null) {
            bootstrap.awaitShutdown();
        }
    }

    /**
     * Asks the current deployment to stop and returns at once: the programmatic counterpart of Ctrl+C or SIGTERM,
     * without {@code System.exit}. The stop runs on a new virtual thread: the extensions stop in reverse start
     * order, the container closes (which fires the CDI {@code Shutdown} event) and {@link #waitForExit()} returns,
     * so the {@code main} thread can end and the JVM exits on its own. If the JVM starts exiting first, its
     * shutdown hook waits for this stop to complete.
     *
     * <p>Safe to call from anywhere, a UI thread, an HTTP request thread, a CDI observer or an extension's
     * {@code onStart} / {@code onStop}: the caller never waits for the stop, so it cannot wait for itself (an
     * extension that hands work to its own thread and waits for it, or a server draining its requests, would
     * otherwise deadlock on the caller). Calling it again does not repeat the stop. Does nothing when no deployment
     * is running. A request made while the deployment boots is honoured as soon as the boot completes.
     *
     * <p>The caller gets no failure back: a stop that fails is logged at ERROR by the runtime. Use
     * {@link #shutdownAndWait()} to wait for the stop and receive its failure.
     */
    public static void shutdown() {
        var bootstrap = CURRENT.get();
        if (bootstrap != null) {
            Thread.ofVirtual().name("vidocq-shutdown-request").start(() -> {
                try {
                    bootstrap.shutdown();
                } catch (RuntimeException _) {
                    // already logged by the bootstrap; nobody is left to receive it
                }
            });
        }
    }

    /**
     * Stops the current deployment on the calling thread and returns once it is stopped, or once an already running
     * stop (the JVM hook's, another caller's) completes. <strong>Never call it from a thread the stop needs</strong>:
     * a UI thread, an HTTP request thread, an executor of the application. Called by the stopping thread itself, from
     * an {@code onStop} or a {@code Shutdown} observer, it returns at once; called by the booting thread, from an
     * {@code onStart}, it returns at once and the stop follows when the boot completes.
     *
     * @throws RuntimeException the container raised while closing; it is also logged at ERROR, and
     *                          {@link #waitForExit()} is released all the same
     */
    public static void shutdownAndWait() {
        var bootstrap = CURRENT.get();
        if (bootstrap != null) {
            bootstrap.shutdown();
        }
    }

    /** Makes {@code bootstrap} the target of {@link #shutdown()} and {@link #waitForExit()}. */
    static void register(VidocqBootstrap bootstrap) {
        CURRENT.set(bootstrap);
    }

    /** Forgets the registered deployment; for tests that need a clean slate. */
    static void unregister() {
        CURRENT.set(null);
    }

    /**
     * The application module, or {@code null}: the module of the class that called {@link #run}, else
     * the module of {@code -Dvidocq.app.main}, else the module {@code -Dvidocq.app.module} names (in the application
     * layer, else in the boot layer), else the {@code -m} module unless it is the runtime's. Never guessed among the
     * application archives.
     */
    static Module applicationModule() {
        Module caller = APPLICATION_MODULE.get();
        if (caller != null && caller.isNamed()) {
            return caller;
        }
        Module main = moduleOfClass(System.getProperty(VidocqAppLayer.APP_MAIN_PROPERTY, "").strip());
        if (main != null) {
            return main;
        }
        String named = System.getProperty(VidocqAppLayer.APP_MODULE_PROPERTY, "").strip();
        if (!named.isEmpty()) {
            // The application layer first, which looks in the boot layer after its own modules: @VidocqTest names the
            // module and hands its classes through vidocq.app.path, off the boot layer (BUG-20261114-07).
            ModuleLayer installed = VidocqAppLayer.installedLayer();
            Module module = (installed == null ? ModuleLayer.boot() : installed).findModule(named).orElse(null);
            if (module != null) {
                return module;
            }
        }
        String launched = System.getProperty("jdk.module.main");
        if (launched == null || launched.isBlank() || launched.startsWith("io.vidocq.runtime.")) {
            return null;
        }
        return ModuleLayer.boot().findModule(launched).orElse(null);
    }

    /** The module holding the package of {@code className}, found without loading the class. */
    private static Module moduleOfClass(String className) {
        int dot = className.lastIndexOf('.');
        if (dot <= 0) {
            return null;
        }
        String packageName = className.substring(0, dot);
        for (ModuleLayer layer : java.util.Arrays.asList(VidocqAppLayer.installedLayer(), ModuleLayer.boot())) {
            if (layer == null) {
                continue;
            }
            for (Module module : layer.modules()) {
                if (module.getPackages().contains(packageName)) {
                    return module;
                }
            }
        }
        return null;
    }

    static void ensureLayer(Class<?> caller) {
        if (VidocqAppLayer.alreadyInLayer()) {
            return;
        }
        if (VidocqAppLayer.installIfConfigured()) {          // explicit -Dvidocq.app.path wins
            return;
        }
        if (Boolean.getBoolean(LayerSplit.OFF_PROPERTY)) {
            // A launch without a layer (vidocq.dev.layer=false, vidocq.package.layer=false): the application is the
            // module the JVM resolved, never a second copy of it in a layer (BUG-20261114-10).
            LOG.log(System.Logger.Level.INFO, () -> LayerSplit.OFF_PROPERTY + "=true: no application layer, "
                    + caller.getModule() + " runs from the boot layer");
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
        var bootstrap = VidocqBootstrap.create().configure();
        register(bootstrap); // before the boot: an onStart may ask for the shutdown
        return bootstrap.start();
    }

    /** Re-loads {@code appClass} through the layer and instantiates it there. */
    private static VidocqApp instantiateInLayer(Class<? extends VidocqApp> appClass)
            throws ReflectiveOperationException {
        var tccl = Thread.currentThread().getContextClassLoader();
        Class<?> layerClass = Class.forName(appClass.getName(), true, tccl);
        // As a CDI bean when the container knows it…
        try {
            var instance = jakarta.enterprise.inject.spi.CDI.current().select(layerClass);
            if (instance.isResolvable()) {
                return (VidocqApp) instance.get();
            }
        } catch (RuntimeException _) {
            // fall through to plain instantiation
        }
        // …otherwise through its public no-arg constructor (package exported to us
        // through the layer controller — the JDK-launcher technique).
        VidocqAppLayer.exportToRuntime(layerClass);
        var problem = inaccessibleApplicationClass(layerClass, Vidocq.class.getModule());
        if (problem != null) {
            throw new IllegalStateException(problem);
        }
        return (VidocqApp) layerClass.getDeclaredConstructor().newInstance();
    }

    /**
     * Why the runtime cannot instantiate {@code appClass}, or {@code null} when it can: its package must be
     * exported to the runtime, which {@link VidocqAppLayer#exportToRuntime} grants only to a class re-layered
     * into the application layer. An application left in the boot layer (no application module found) gets
     * this message instead of a bare {@code IllegalAccessException} (vidocq#201, BUG-20260815-01).
     */
    static String inaccessibleApplicationClass(Class<?> appClass, Module runtime) {
        var module = appClass.getModule();
        var pkg = appClass.getPackageName();
        if (!module.isNamed() || module.isExported(pkg, runtime)) {
            return null;
        }
        return "Vidocq cannot instantiate " + appClass.getName() + ": module " + module.getName()
                + " does not export " + pkg + " to io.vidocq.runtime.core, and the application was not moved into"
                + " the Vauban application layer, where that export is granted automatically. Add"
                + " `exports " + pkg + " to io.vidocq.runtime.core;` to " + module.getName()
                + "'s module-info.java, or make " + appClass.getSimpleName() + " a CDI bean"
                + " (an @ApplicationScoped or @Dependent class), which the container instantiates itself.";
    }
}
