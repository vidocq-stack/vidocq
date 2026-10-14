package acme.app;

import io.vidocq.runtime.core.Vidocq;

/**
 * Deliberately without {@code @VidocqMain}: with it, vidocq:package writes the trampoline script, which keeps
 * automatic modules in the boot layer, so the packaged run would not exercise {@code vidocq.app.layer=all}.
 */
public final class AppLayerApp {

    private AppLayerApp() {}

    public static void main(String[] args) {
        Vidocq.run(args);
    }
}
