module io.vidocq.runtime.examples.extlib {
    requires jakarta.cdi;
    requires jakarta.ws.rs;

    exports io.vidocq.runtime.examples.extlib;

    // CDI / JAX-RS reflection
    opens io.vidocq.runtime.examples.extlib;
}
