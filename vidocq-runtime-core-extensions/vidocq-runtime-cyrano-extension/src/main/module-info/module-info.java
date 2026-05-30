/**
 * Vidocq Runtime wrapper that activates Cyrano (MicroProfile Rest Client 4.0)
 * via its standard SPIs (RestClientBuilderResolver + BCE CDI 4.1).
 */
module io.vidocq.runtime.ext.cyrano {
    requires transitive io.vidocq.cyrano.api;
    requires transitive io.vidocq.cyrano.core;
    requires transitive io.vidocq.cyrano.cdi.vauban;

    requires jakarta.cdi;

    provides jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension
            with io.vidocq.runtime.ext.cyrano.CyranoBuildCompatibleExtension;
}

