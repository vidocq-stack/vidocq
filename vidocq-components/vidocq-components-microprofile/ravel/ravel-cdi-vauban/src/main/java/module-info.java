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

    provides jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension
            with io.vidocq.ravel.cdi.ConfigCdiExtension;

    provides jakarta.enterprise.inject.spi.Extension
            with io.vidocq.ravel.cdi.ConfigPropertiesExclusionExtension;

    exports io.vidocq.ravel.cdi;
    exports io.vidocq.ravel.cdi.internal;
}
