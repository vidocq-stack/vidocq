module acme.app {
    requires io.vidocq.runtime.core;
    requires jakarta.cdi;
    requires acme.pages;
    requires acme.sql;

    exports acme.app;
}
