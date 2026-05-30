package io.vidocq.runtime.core;

/**
 * Main entry point to the Vidocq server.
 * <p><b>Main entry point for the Vidocq server.</b></p>
 *
 * <pre>{@code
 * java -m io.vidocq.runtime.core/io.vidocq.runtime.core.Vidocq
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
