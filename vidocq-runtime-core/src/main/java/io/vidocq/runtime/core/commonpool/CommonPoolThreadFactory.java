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
package io.vidocq.runtime.core.commonpool;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The thread factory of the JVM's common pool under a Vidocq launch: it gives each worker the application's class
 * loader as its context class loader (BUG-20261118-93).
 *
 * <p>{@code CompletableFuture.supplyAsync}, {@code parallelStream} and every task with no executor of its own run on
 * the common pool. The JDK gives its workers the JVM's system class loader, which misses an application that runs in
 * an application layer: a library that reads through the context class loader of such a task (MicroProfile Config,
 * {@code ServiceLoader}, JAXB, a JDBC driver) does not find the application's resources or services there. The
 * launchers pass {@value #PROPERTY}; {@code VidocqAppLayer} sets it too when it is absent, and names the launch in a
 * warning when the pool started before and kept the JDK's factory.
 *
 * <p>The workers are the platform threads the JDK's pool is made of, one of the documented exceptions of the
 * architecture rule on platform threads. They live past a dev reload: {@link #useLoader} moves the live ones to the
 * new layer, and to the loader before the layer while there is none, so that no worker holds a discarded layer.
 *
 * <p>The JDK loads this class by name through the system class loader and calls its public no-argument constructor:
 * its package is exported to {@code java.base} only.
 */
public final class CommonPoolThreadFactory implements ForkJoinPool.ForkJoinWorkerThreadFactory {

    /** The JDK property that names the factory of the common pool, read once, when the pool starts. */
    public static final String PROPERTY = "java.util.concurrent.ForkJoinPool.common.threadFactory";

    /** The application's loader, or {@code null} when no application layer is installed. */
    private static final AtomicReference<ClassLoader> APPLICATION_LOADER = new AtomicReference<>();

    /** The JDK calls it, by reflection, when the common pool starts. */
    public CommonPoolThreadFactory() {
        // stateless: the loader is the one of the installed layer at each worker's creation
    }

    @Override
    public ForkJoinWorkerThread newThread(ForkJoinPool pool) {
        return new Worker(pool, loader());
    }

    /**
     * Sets {@value #PROPERTY} to this class when nothing set it, before the common pool starts. Too late when
     * something used the pool before: {@link #installed()} says so.
     */
    public static void prepare() {
        if (System.getProperty(PROPERTY) == null) {
            System.setProperty(PROPERTY, CommonPoolThreadFactory.class.getName());
        }
    }

    /** Whether the common pool makes its workers with this factory. Starts the pool. */
    public static boolean installed() {
        return ForkJoinPool.commonPool().getFactory() instanceof CommonPoolThreadFactory;
    }

    /**
     * Gives the workers created from now on, and the live ones, {@code loader} as their context class loader:
     * {@code VidocqAppLayer} calls it with the loader of the layer it installs, and with the loader before the layer
     * when a dev reload discards it.
     *
     * @param loader the application's loader, or {@code null} for the system class loader
     */
    public static void useLoader(ClassLoader loader) {
        APPLICATION_LOADER.set(loader);
        ClassLoader target = loader();
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread instanceof Worker worker) {
                worker.setContextClassLoader(target);
            }
        }
    }

    /** The context class loader a worker gets: the application's, else the system class loader, as the JDK gives. */
    static ClassLoader loader() {
        ClassLoader loader = APPLICATION_LOADER.get();
        return loader != null ? loader : ClassLoader.getSystemClassLoader();
    }

    /** A worker of the common pool, as the JDK's own, but for its context class loader. */
    static final class Worker extends ForkJoinWorkerThread {

        Worker(ForkJoinPool pool, ClassLoader loader) {
            // a null group: the current one; true: thread locals stay, as on the JDK's own workers of the common pool
            super(null, pool, true);
            setContextClassLoader(loader);
        }
    }
}
