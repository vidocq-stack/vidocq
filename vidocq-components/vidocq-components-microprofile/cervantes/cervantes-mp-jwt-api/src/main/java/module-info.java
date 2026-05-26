/**
 * Descripteur de module explicite pour la spec MicroProfile JWT 2.1.
 *
 * <p>L'artefact officiel {@code org.eclipse.microprofile.jwt:microprofile-jwt-auth-api:2.1}
 * publié par la fondation Eclipse ne fournit NI {@code module-info.class} NI
 * {@code Automatic-Module-Name} ; jlink refuse ce type de module pour la composition d'un
 * runtime image. Ce module-info l'érige en module explicite, sans modifier le code de la spec.
 *
 * <p>Nom de module choisi : {@code org.eclipse.microprofile.jwt} (aligné sur le package
 * principal). Comme heisenberg-mp-ft-api, ce descripteur ne déclare que des {@code exports}
 * et aucun {@code requires} : la compilation de ce seul {@code module-info} (via
 * {@code --patch-module}) ne valide que l'existence des packages exportés (fournis par les
 * {@code .class} dépaquetés). Les dépendances réelles de la spec ({@code jakarta.json},
 * {@code jakarta.cdi}) sont déclarées par les modules consommateurs (cervantes-api,
 * cervantes-core, cervantes-cdi-vauban), qui en ont besoin à la compilation de leur propre code.
 */
module org.eclipse.microprofile.jwt {
    exports org.eclipse.microprofile.auth;
    exports org.eclipse.microprofile.jwt;
    exports org.eclipse.microprofile.jwt.config;
}
