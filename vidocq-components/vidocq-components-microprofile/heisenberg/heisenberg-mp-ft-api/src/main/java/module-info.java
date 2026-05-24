/**
 * Descripteur de module explicite pour la spec MicroProfile Fault Tolerance 4.1.
 *
 * <p>L'artefact officiel {@code org.eclipse.microprofile.fault-tolerance:microprofile-fault-tolerance-api}
 * publié par la fondation Eclipse ne fournit pas de {@code module-info.class} ; jlink refuse
 * ce type de module pour la composition d'un runtime image. Ce module-info l'érige en module
 * explicite, sans modifier le code de la spec, en conservant exactement le même nom de module
 * ({@code microprofile.fault.tolerance.api}) afin que tout {@code requires} existant continue
 * à fonctionner.
 *
 * <p>Le nom {@code microprofile.fault.tolerance.api} est celui que le JDK dérivait jusqu'ici
 * du nom de fichier {@code microprofile-fault-tolerance-api-4.1.jar} par la règle
 * JPMS "strip version + replace '-' with '.'" — il n'y a donc aucune migration nécessaire
 * dans les modules qui déclaraient déjà {@code requires microprofile.fault.tolerance.api}.
 */
module microprofile.fault.tolerance.api {
    exports org.eclipse.microprofile.faulttolerance;
    exports org.eclipse.microprofile.faulttolerance.exceptions;
}
