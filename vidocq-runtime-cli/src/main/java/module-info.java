module io.vidocq.runtime.cli {
    requires io.vidocq.runtime.core;

    // CLI lists installed extensions via ServiceLoader — each consuming module
    // must declare its own `uses` even when an upstream module already does.
    uses io.vidocq.runtime.spi.VidocqExtension;

    exports io.vidocq.runtime.cli;
}
