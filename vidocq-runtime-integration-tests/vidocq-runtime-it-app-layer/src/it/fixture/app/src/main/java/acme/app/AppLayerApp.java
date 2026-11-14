package acme.app;

import io.vidocq.runtime.core.Vidocq;

/**
 * A trampoline main, which the fixture names as {@code vidocq.mainClass}: the packaged launchers hand it to the
 * runtime as {@code vidocq.app.main}, and the launches without a layer ({@code vidocq.package.layer=false},
 * {@code vidocq.dev.layer=false}) run it from the module path.
 */
public final class AppLayerApp {

    private AppLayerApp() {}

    public static void main(String[] args) {
        Vidocq.run(args);
    }
}
