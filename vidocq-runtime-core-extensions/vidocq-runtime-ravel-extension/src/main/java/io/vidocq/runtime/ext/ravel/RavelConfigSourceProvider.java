package io.vidocq.runtime.ext.ravel;

import io.vidocq.runtime.spi.config.ConfigSource;
import io.vidocq.runtime.spi.config.ConfigSourceProvider;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;

/**
 * Implémentation Ravel du SPI Vidocq {@link ConfigSourceProvider}.
 *
 * <p>Au démarrage du runtime, {@code VidocqConfigImpl} découvre cette classe via
 * {@link java.util.ServiceLoader}. Sa présence dans le module path bascule
 * Vidocq en mode délégation : les {@link ConfigSource} natifs Vidocq (Sys, Env,
 * ExternalFile, PropertiesFile) sont ignorés au profit des sources exposées par
 * MP Config — sys properties (ordinal 400), env vars (300),
 * {@code microprofile-config.properties} (100), {@code vidocq.properties} (105),
 * {@code application.properties} (100), plus toutes les sources MP custom user
 * (profils {@code %dev.}, expressions {@code ${}}, etc.).</p>
 *
 * <p>L'extension apporte également la BCE {@code ConfigCdiExtension} via la
 * transitive {@code ravel-cdi-vauban}, ce qui active automatiquement
 * {@code @Inject @ConfigProperty} dans le container Vauban.</p>
 */
public final class RavelConfigSourceProvider implements ConfigSourceProvider {

    @Override
    public String getName() {
        return "RavelConfigSourceProvider";
    }

    @Override
    public Iterable<ConfigSource> getConfigSources(ClassLoader cl) {
        ClassLoader loader = cl != null ? cl : Thread.currentThread().getContextClassLoader();
        if (loader == null) loader = RavelConfigSourceProvider.class.getClassLoader();

        Config mpConfig = ConfigProviderResolver.instance().getConfig(loader);

        List<ConfigSource> wrapped = new ArrayList<>();
        for (org.eclipse.microprofile.config.spi.ConfigSource mp : mpConfig.getConfigSources()) {
            wrapped.add(new MpConfigSourceAdapter(mp));
        }
        return wrapped;
    }
}
