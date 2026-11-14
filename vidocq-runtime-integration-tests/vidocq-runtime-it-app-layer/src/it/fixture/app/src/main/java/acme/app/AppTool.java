package acme.app;

import java.io.PrintWriter;
import java.util.spi.ToolProvider;

/**
 * A service the application provides: {@link Probe} counts the providers of this type that the context loader
 * finds. One copy of the application module finds one; a launch that leaves the module in the boot layer and loads
 * it again in the application layer finds two (BUG-20261114-10).
 */
public final class AppTool implements ToolProvider {

    /** The name {@link Probe} reports. */
    static final String TYPE = AppTool.class.getName();

    @Override
    public String name() {
        return "acme-app-tool";
    }

    @Override
    public int run(PrintWriter out, PrintWriter err, String... args) {
        return 0;
    }
}
