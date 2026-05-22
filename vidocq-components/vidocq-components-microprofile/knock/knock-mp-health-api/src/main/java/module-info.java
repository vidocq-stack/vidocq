module microprofile.health.api {
    requires java.logging;
    requires static jakarta.cdi;
    requires static jakarta.inject;

    exports org.eclipse.microprofile.health;
    exports org.eclipse.microprofile.health.spi;

    uses org.eclipse.microprofile.health.spi.HealthCheckResponseProvider;
}
