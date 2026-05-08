/**
 * Intégration CDI de Ravel pour le container Vauban — fournit le support
 * {@code @Inject @ConfigProperty} via une Build Compatible Extension.
 *
 * <p>Module optionnel : un déploiement standalone SE n'a pas besoin de ce module
 * et peut consommer {@code ravel-core} directement via {@code ConfigProvider.getConfig()}.</p>
 */
module io.vidocq.ravel.cdi.vauban {
    requires transitive io.vidocq.ravel.core;

    requires static jakarta.cdi;
    requires static jakarta.inject;
    requires static jakarta.annotation;

    provides jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension
            with io.vidocq.ravel.cdi.ConfigCdiExtension;

    exports io.vidocq.ravel.cdi;
}
