package io.vidocq.runtime.spi.config;

/**
 * Fournit un ensemble de {@link ConfigSource} au runtime Vidocq.
 *
 * <p>Découvert via {@link java.util.ServiceLoader} au démarrage de
 * {@code VidocqConfigImpl}. Cette SPI permet de brancher un moteur de
 * configuration tiers (MicroProfile Config via Ravel, HashiCorp Consul,
 * Spring Cloud Config, etc.) sans coupler {@code vidocq-runtime-core} à
 * l'implémentation choisie.</p>
 *
 * <p><b>Sémantique de remplacement</b> — si <em>au moins un</em>
 * {@code ConfigSourceProvider} est enregistré au démarrage, ses sources
 * <b>remplacent intégralement</b> les {@link ConfigSource} natifs Vidocq
 * découverts via {@code ServiceLoader<ConfigSource>}. Le provider est donc
 * responsable d'apporter les substituts nécessaires (system properties,
 * environment variables, fichiers de propriétés, etc.) — Vidocq ne mixe pas
 * les deux pour éviter le double comptage et préserver la sémantique
 * d'ordinaux de l'écosystème externe (ex. priorités MP Config).</p>
 *
 * <p>Quand plusieurs providers sont présents, l'union de leurs sources est
 * utilisée, triée par {@link ConfigSource#getOrdinal()} décroissant.</p>
 *
 * <p><b>Cas d'usage typique</b> — l'extension
 * {@code vidocq-runtime-ravel-extension} fournit un
 * {@code RavelConfigSourceProvider} qui itère sur les
 * {@code org.eclipse.microprofile.config.spi.ConfigSource} exposés par
 * {@code ConfigProvider.getConfig()} et les wrappe en {@link ConfigSource}
 * Vidocq. Quand l'extension est sur le module path, Ravel prend la main ; quand
 * elle est absente, les 4 sources natives Vidocq (Sys, Env, ExternalFile,
 * PropertiesFile) sont utilisées.</p>
 *
 * <p><b>Vidocq config source provider.</b>
 * Contributes a set of {@link ConfigSource} instances to the Vidocq runtime.
 * Discovered via {@link java.util.ServiceLoader}. If at least one provider is
 * registered, it fully replaces the native Vidocq ConfigSources — the provider
 * is responsible for supplying equivalent substitutes.</p>
 */
public interface ConfigSourceProvider {

    /**
     * Nom diagnostic du provider (utilisé pour les logs et l'introspection).
     * <p>Provider's diagnostic name.</p>
     */
    String getName();

    /**
     * Sources contribuées par ce provider. Le {@link ClassLoader} courant est
     * passé pour permettre aux moteurs externes (MP Config, Spring, etc.) de
     * résoudre leur configuration dans le bon contexte de chargement.
     *
     * <p>Contributed sources. The current ClassLoader is passed so external
     * engines can resolve their configuration in the proper loading context.</p>
     */
    Iterable<ConfigSource> getConfigSources(ClassLoader cl);
}
