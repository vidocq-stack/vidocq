/**
 * Descripteur de module explicite pour la spec MicroProfile Config 3.1.
 *
 * <p>L'artefact officiel {@code org.eclipse.microprofile.config:microprofile-config-api}
 * publié par la fondation Eclipse ne fournit qu'un {@code Automatic-Module-Name} dans
 * son manifest ; jlink refuse ce type de module pour la composition d'un runtime image.
 * Ce module-info l'érige en module explicite, sans modifier le code de la spec, en
 * conservant exactement le même nom de module ({@code org.eclipse.microprofile.config})
 * afin que tout {@code requires} existant continue à fonctionner.
 *
 * <p>Le {@code uses ConfigProviderResolver} reflète le mécanisme défini par
 * {@code ConfigProvider#getConfig()} qui résout son resolver via {@link java.util.ServiceLoader}
 * (cf. MicroProfile Config 3.1 §3, classe {@code org.eclipse.microprofile.config.spi.ConfigProviderResolver}).
 */
module org.eclipse.microprofile.config {
    exports org.eclipse.microprofile.config;
    exports org.eclipse.microprofile.config.inject;
    exports org.eclipse.microprofile.config.spi;

    uses org.eclipse.microprofile.config.spi.ConfigProviderResolver;
}

