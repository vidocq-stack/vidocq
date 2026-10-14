package acme.app;

import io.vidocq.runtime.core.Vidocq;

/** No listener of its own and no subclass of the library's: only the library's {@code @WebListener} can print. */
public final class DependencyCodegenApp {

    private DependencyCodegenApp() {}

    public static void main(String[] args) {
        Vidocq.run(args);
    }
}
