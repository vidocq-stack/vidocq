package fr.vidocq.vidocq.core;

import fr.vidocq.vauban.core.container.VaubanContainer;
import fr.vidocq.vauban.core.container.VaubanContainerBuilder;
import fr.vidocq.vidocq.spi.ExtensionContext;
import fr.vidocq.vidocq.spi.VidocqConfiguration;
import fr.vidocq.vidocq.spi.VidocqExtension;

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

    private VidocqConfiguration configuration;
    private List<VidocqExtension> extensions = List.of();
    private VaubanContainer container;

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
        LOG.log(System.Logger.Level.INFO, "Vidocq - Configuration phase");

        this.configuration = new VidocqConfigurationImpl();
        this.extensions = ExtensionLoader.load();

        for (VidocqExtension ext : extensions) {
            ext.configure(configuration);
        }

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

        for (VidocqExtension ext : extensions) {
            ext.beforeStart(builder);
        }

        this.container = builder.build();

        // Notify extensions
        ExtensionContext context = new ExtensionContextImpl(container, configuration);
        for (VidocqExtension ext : extensions) {
            LOG.log(System.Logger.Level.INFO, "Starting extension: {0}", ext.name());
            ext.onStart(context);
        }

        // Shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "vidocq-shutdown"));

        LOG.log(System.Logger.Level.INFO, "Vidocq - Started");
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

    private void shutdown() {
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
