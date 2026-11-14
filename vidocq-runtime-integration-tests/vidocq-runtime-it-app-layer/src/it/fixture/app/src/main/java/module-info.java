module acme.app {
    requires io.vidocq.runtime.core;
    requires jakarta.cdi;
    requires acme.pages;
    requires acme.sql;

    exports acme.app;
    // The launches without a layer (vidocq.package.layer=false, vidocq.dev.layer=false) run this module from the boot
    // layer, where no Vauban loader promotes its generated providers: the container reaches Probe by reflection.
    opens acme.app to io.vidocq.vauban.core;

    // Probe counts the copies of the application module a launch loaded (BUG-20261114-10).
    uses java.util.spi.ToolProvider;
    provides java.util.spi.ToolProvider with acme.app.AppTool;
}
