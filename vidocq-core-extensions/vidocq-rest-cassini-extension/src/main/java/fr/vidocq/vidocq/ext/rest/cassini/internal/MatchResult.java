package fr.vidocq.vidocq.ext.rest.cassini.internal;

import java.util.Map;

/**
 * Résultat d'un match {@link UriRouter} : la {@link ResourceMethod} choisie
 * et les valeurs capturées par les templates URI (noms → valeurs non
 * décodées, conformément à JAX-RS §3.1.1).
 */
public record MatchResult(ResourceMethod method, Map<String, String> pathParams) {
}
