module io.vidocq.mpserver.examples.extlib {
    requires jakarta.cdi;
    requires jakarta.ws.rs;

    exports io.vidocq.mpserver.examples.extlib;

    // CDI / JAX-RS reflection
    opens io.vidocq.mpserver.examples.extlib;
}
