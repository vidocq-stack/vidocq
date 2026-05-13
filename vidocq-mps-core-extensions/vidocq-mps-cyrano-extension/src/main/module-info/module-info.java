/**
 * Wrapper Vidocq-MPS qui active Cyrano (MicroProfile Rest Client 4.0)
 * via ses SPI standards (RestClientBuilderResolver + BCE CDI 4.1).
 */
module io.vidocq.mpserver.ext.cyrano {
    requires transitive io.vidocq.cyrano.api;
    requires transitive io.vidocq.cyrano.core;
    requires transitive io.vidocq.cyrano.cdi.vauban;

    requires jakarta.cdi;

    provides jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension
            with io.vidocq.mpserver.ext.cyrano.CyranoBuildCompatibleExtension;
}

