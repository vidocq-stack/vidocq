/**
 * CDI integration module for Vauban, providing {@code @Inject @ConfigProperty}
 * support through a Build Compatible Extension.
 *
 * <p>This module is optional: standalone Java SE deployments can use
 * {@code ravel-core} directly through {@code ConfigProvider.getConfig()}.</p>
 */
module io.vidocq.ravel.cdi.vauban {
    requires transitive io.vidocq.ravel.core;

    requires static jakarta.cdi;
    requires static jakarta.inject;
    requires static jakarta.annotation;
    // Compile-only (optional at runtime): supplies the VaubanComponentProvider service type.
    requires static io.vidocq.vauban.api;

    provides jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension
            with io.vidocq.ravel.cdi.ConfigCdiExtension;

    provides jakarta.enterprise.inject.spi.Extension
            with io.vidocq.ravel.cdi.ConfigPropertiesExclusionExtension;

    // In-module instantiation and producer invocation of this package's beans (the @Produces Config
    // in RavelConfigProducer), generated as _VaubanComponents co-located in io.vidocq.ravel.cdi — so
    // the container needs no `opens … to io.vidocq.vauban.core`. APT-generated, inert under Weld.
    provides io.vidocq.vauban.api.VaubanComponentProvider
            with io.vidocq.ravel.cdi._VaubanComponents;

    exports io.vidocq.ravel.cdi;
    exports io.vidocq.ravel.cdi.internal;
}
