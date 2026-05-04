/**
 * CDI 4.1 bootstrap for Mansart Data — BuildCompatibleExtension reading {@code META-INF/mansart-repositories.list}.
 * Implementation in M3.
 */
module io.vidocq.mansart.data.cdi {
    requires transitive io.vidocq.mansart.data.core;
    requires jakarta.cdi;
    requires jakarta.inject;

    exports io.vidocq.mansart.data.cdi;
}
