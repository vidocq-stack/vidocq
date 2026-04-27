package io.vidocq.mpserver.core;

/**
 * Point d'entrée principal du serveur Vidocq.
 * <p><b>Main entry point for the Vidocq server.</b></p>
 *
 * <pre>{@code
 * java -m io.vidocq.mpserver.core/io.vidocq.mpserver.core.Vidocq
 * }</pre>
 */
public final class Vidocq {

    private Vidocq() {}

    public static void main(String[] args) {
        VidocqBootstrap.create()
                .configure()
                .start()
                .awaitShutdown();
    }
}
