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

import io.vidocq.vauban.core.container.VaubanContainer;
import io.vidocq.vauban.core.container.VaubanContainerBuilder;
import io.vidocq.runtime.core.config.VidocqConfigImpl;
import io.vidocq.runtime.spi.ExtensionContext;
import io.vidocq.runtime.spi.VidocqConfiguration;
import io.vidocq.runtime.spi.VidocqExtension;
import io.vidocq.runtime.spi.config.VidocqConfig;

import java.lang.management.ManagementFactory;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Vidocq lifecycle orchestrator.
 * <p>
 * Startup sequence:
 * <ol>
 *   <li>Loading configuration</li>
 *   <li>Discovery of extensions (ServiceLoader, sorted by priority)</li>
 *   <li>{@code extension.configure(config)}</li>
 *   <li>Creation of the {@link VaubanContainerBuilder}, {@code extension.beforeStart(builder)}</li>
 *   <li>Build the {@link VaubanContainer} (CDI boot)</li>
 *   <li>{@code extension.onStart(context)}</li>
 *   <li>Registering the shutdown hook</li>
 *   <li>Block on {@link #awaitShutdown()}</li>
 * </ol>
 *
 * <p><b>Vidocq lifecycle orchestrator.</b></p>
 */
public final class VidocqBootstrap {

    private static final System.Logger LOG = System.getLogger(VidocqBootstrap.class.getName());

    private final CountDownLatch shutdownLatch = new CountDownLatch(1);

    private VidocqConfig config;
    private VidocqConfiguration configuration;
    private List<VidocqExtension> extensions = List.of();
    private List<String> additionalBeanClassNames;
    private VaubanContainer container;
    /**
     * Top-chrono taken during the construction of the bootstrap (= just after the entry
     * from {@code Vidocq.main()} via {@link #create()}). Comparable to {@code
     * StopWatch} started by {@code SpringApplication.run()} of Spring Boot.
     */
    private final long startTime = System.nanoTime();

    private VidocqBootstrap() {}

    /**
     * Creates a new bootstrap instance.
     */
    public static VidocqBootstrap create() {
        return new VidocqBootstrap();
    }

    /**
     * Phase 1: loads the configuration and discovers the extensions.
     */
    public VidocqBootstrap configure() {
        LOG.log(System.Logger.Level.INFO, "Vidocq - Configuration phase");

        this.config = new VidocqConfigImpl();
        this.configuration = new VidocqConfigurationImpl(config);
        this.extensions = ExtensionLoader.load();

        for (VidocqExtension ext : extensions) {
            ext.configure(configuration);
        }

        return this;
    }

    /**
     * Phase 1 (variant): configure with additional bean classes.
     * Used by the Arquillian container to inject deployment classes.
     */
    public VidocqBootstrap configure(java.util.List<String> additionalBeanClassNames) {
        configure();
        this.additionalBeanClassNames = additionalBeanClassNames;
        return this;
    }

    /**
     * Phase 2: booting the CDI container and starting the extensions.
     */
    public VidocqBootstrap start() {
        LOG.log(System.Logger.Level.INFO, "Vidocq - Starting");

        // Build CDI container
        VaubanContainerBuilder builder = VaubanContainer.builder()
                .scanClasspath();

        // Add extra bean classes (e.g. from Arquillian deployment)
        if (additionalBeanClassNames != null) {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            for (String className : additionalBeanClassNames) {
                try {
                    builder.addBeanClass(cl.loadClass(className));
                } catch (ClassNotFoundException | LinkageError e) {
                    // A bean class we cannot load or link is skipped, not fatal. This
                    // covers infrastructure classes bundled in a deployment's
                    // WEB-INF/lib (e.g. a TCK harness that packages a Maven resolver)
                    // whose optional transitive references are absent — they are not
                    // application beans, so dropping them must not abort the boot.
                    LOG.log(System.Logger.Level.WARNING,
                            "Skipping bean class that cannot be loaded/linked: " + className + " (" + e + ")");
                }
            }
        }

        for (VidocqExtension ext : extensions) {
            ext.beforeStart(builder);
        }

        this.container = builder.build();

        // Notify extensions
        ExtensionContext context = new ExtensionContextImpl(container, configuration, config);
        for (VidocqExtension ext : extensions) {
            LOG.log(System.Logger.Level.INFO, "Starting extension: {0}", ext.name());
            ext.onStart(context);
        }

        // Shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "vidocq-shutdown"));

        long elapsed = System.nanoTime() - startTime;
        long ms = elapsed / 1_000_000;
        long us = (elapsed / 1_000) % 1_000;
        long jvmUptime = ManagementFactory.getRuntimeMXBean().getUptime();
        LOG.log(System.Logger.Level.INFO,
                "Vidocq - Started in " + ms + "." + String.format("%03d", us)
                        + " ms (process running for " + jvmUptime + " ms)");
        return this;
    }

    /**
     * Blocks the current thread until the server stops.
     */
    public void awaitShutdown() {
        try {
            shutdownLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            shutdown();
        }
    }

    public void shutdown() {
        LOG.log(System.Logger.Level.INFO, "Vidocq - Shutting down");

        // Stop extensions in reverse order
        List<VidocqExtension> reversed = new java.util.ArrayList<>(extensions);
        Collections.reverse(reversed);
        for (VidocqExtension ext : reversed) {
            try {
                ext.onStop();
            } catch (Exception e) {
                LOG.log(System.Logger.Level.ERROR, "Error stopping extension: " + ext.name(), e);
            }
        }

        // Close CDI container
        if (container != null) {
            container.close();
        }

        shutdownLatch.countDown();
        LOG.log(System.Logger.Level.INFO, "Vidocq - Stopped");
    }
}
