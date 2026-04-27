package io.vidocq.mpserver.core;

import io.vidocq.vauban.core.container.VaubanContainer;
import io.vidocq.vauban.core.container.VaubanContainerBuilder;
import io.vidocq.mpserver.core.config.VidocqConfigImpl;
import io.vidocq.mpserver.spi.ExtensionContext;
import io.vidocq.mpserver.spi.VidocqConfiguration;
import io.vidocq.mpserver.spi.VidocqExtension;
import io.vidocq.mpserver.spi.config.VidocqConfig;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Orchestrateur du cycle de vie Vidocq.
 * <p>
 * Séquence de démarrage :
 * <ol>
 *   <li>Chargement de la configuration</li>
 *   <li>Découverte des extensions (ServiceLoader, triées par priorité)</li>
 *   <li>{@code extension.configure(config)}</li>
 *   <li>Création du {@link VaubanContainerBuilder}, {@code extension.beforeStart(builder)}</li>
 *   <li>Build du {@link VaubanContainer} (boot CDI)</li>
 *   <li>{@code extension.onStart(context)}</li>
 *   <li>Enregistrement du shutdown hook</li>
 *   <li>Blocage sur {@link #awaitShutdown()}</li>
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
    private long startTime;

    private VidocqBootstrap() {}

    /**
     * Crée une nouvelle instance de bootstrap.
     */
    public static VidocqBootstrap create() {
        return new VidocqBootstrap();
    }

    /**
     * Phase 1 : charge la configuration et découvre les extensions.
     */
    public VidocqBootstrap configure() {
        this.startTime = System.nanoTime();
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
     * Phase 1 (variante) : configure avec des classes beans additionnelles.
     * Utilisé par le container Arquillian pour injecter les classes du deployment.
     */
    public VidocqBootstrap configure(java.util.List<String> additionalBeanClassNames) {
        configure();
        this.additionalBeanClassNames = additionalBeanClassNames;
        return this;
    }

    /**
     * Phase 2 : boot du container CDI et démarrage des extensions.
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
                } catch (ClassNotFoundException e) {
                    LOG.log(System.Logger.Level.WARNING, "Bean class not found: " + className);
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
        LOG.log(System.Logger.Level.INFO, "Vidocq - Started in " + ms + "." + String.format("%03d", us) + " ms");
        return this;
    }

    /**
     * Bloque le thread courant jusqu'à l'arrêt du serveur.
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
