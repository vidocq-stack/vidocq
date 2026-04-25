package fr.vidocq.vidocq.ext.rest.cassini.internal;

import java.util.List;
import java.util.Map;

/**
 * Résultat d'un match {@link UriRouter} : la {@link ResourceMethod} choisie
 * et les valeurs capturées par les templates URI. Chaque nom logique peut
 * avoir plusieurs valeurs (template répété : /{id}/{id}/{id}) pour supporter
 * l'injection {@code @PathParam List<String>} (§3.3.1).
 */
public record MatchResult(ResourceMethod method, Map<String, List<String>> pathParams) {
}
