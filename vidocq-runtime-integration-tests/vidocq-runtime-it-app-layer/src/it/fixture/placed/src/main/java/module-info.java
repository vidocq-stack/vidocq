module acme.placed {
    requires io.vidocq.runtime.core;
    requires jakarta.cdi;
    requires jakarta.inject;
    requires acme.gadget;

    exports acme.placed;
}
