package io.vidocq.runtime.spi;

import io.vidocq.vauban.core.container.VaubanContainerBuilder;

/**
 * Vidocq's main extension point.
 * <p>
 * Extensions are discovered via {@link java.util.ServiceLoader} and
 * executed according to their {@link #priority() priority} during the lifecycle
 * from the server:
 * <ol>
 *   <li>{@link #configure} — configuration before CDI boot</li>
 *   <li>{@link #beforeStart} — enrichment of the container builder</li>
 *   <li>{@link #onStart} — the CDI container is ready</li>
 *   <li>{@link #onStop} — server shutdown (reverse order)</li>
 * </ol>
 *
 * <p><b>Main extension point for Vidocq.</b>
 * Extensions are discovered via {@link java.util.ServiceLoader} and
 * executed by {@link #priority()} during the server lifecycle.</p>
 */
public interface VidocqExtension {

    /**
     * Unique name of the extension. / Unique extension name.
     */
    String name();

    /**
     * Execution priority (lower = higher priority, default 1000).
     * <p>Execution priority (lower = higher priority, default 1000).</p>
     */
    default int priority() {
        return 1000;
    }

    /**
     * Configuration phase: called before CDI boot.
     * <p>Configuration phase: called before CDI boot.</p>
     */
    default void configure(VidocqConfiguration config) {}

    /**
     * Pre-startup phase: enrich the {@link VaubanContainerBuilder}.
     * <p>Pre-start phase: enrich the {@link VaubanContainerBuilder}.</p>
     */
    default void beforeStart(VaubanContainerBuilder builder) {}

    /**
     * Startup phase: the CDI container is initialized.
     * <p>Start phase: the CDI container is initialized.</p>
     */
    default void onStart(ExtensionContext context) {}

    /**
     * Shutdown phase: release resources.
     * <p>Stop phase: release resources.</p>
     */
    default void onStop() {}
}
