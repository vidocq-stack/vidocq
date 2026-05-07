/**
 * API Ravel : re-exposition contrôlée de la spec MicroProfile Config 3.1 et SPI publique
 * stable pour les extensions tierces (sources de configuration, converters custom, hooks
 * d'observabilité). Le contenu sera étoffé au fil des versions du projet.
 */
module io.vidocq.ravel.api {
    requires transitive org.eclipse.microprofile.config;

    exports io.vidocq.ravel.spi;
}
