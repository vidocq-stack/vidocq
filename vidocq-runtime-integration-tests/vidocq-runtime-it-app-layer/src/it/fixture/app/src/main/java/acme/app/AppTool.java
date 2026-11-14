package acme.app;

import java.io.PrintWriter;
import java.util.spi.ToolProvider;

/**
 * A service the application provides: {@link Probe} counts the providers of this type that the context loader
 * finds. One copy of the application module finds one; a launch that leaves the module in the boot layer and loads
 * it again in the application layer finds two (BUG-20261114-10).
 *
 * <p>Only a {@code public static provider()} method creates it, no public constructor: a form valid in a
 * {@code provides} and not in a {@code META-INF/services} file. A jlink image moves the {@code provides} of the
 * application layer's modules to services files, and the layer must turn them back into {@code provides}, or
 * {@link Probe} fails to create it (BUG-20261114-11).
 */
public final class AppTool implements ToolProvider {

    /** The name {@link Probe} reports. */
    static final String TYPE = AppTool.class.getName();

    private AppTool() {
    }

    /** The provider method {@link java.util.ServiceLoader} calls. */
    public static AppTool provider() {
        return new AppTool();
    }

    @Override
    public String name() {
        return "acme-app-tool";
    }

    @Override
    public int run(PrintWriter out, PrintWriter err, String... args) {
        return 0;
    }
}
