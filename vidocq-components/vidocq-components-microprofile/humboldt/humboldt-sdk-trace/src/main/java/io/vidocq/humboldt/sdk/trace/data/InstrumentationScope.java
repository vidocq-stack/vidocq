package io.vidocq.humboldt.sdk.trace.data;

import io.opentelemetry.api.common.Attributes;

/**
 * Identité de la bibliothèque qui produit un span / metric / log (cf. OpenTelemetry
 * "Instrumentation Scope").
 *
 * @param name       nom de la bibliothèque (jamais {@code null}, peut être vide)
 * @param version    version de la bibliothèque (peut être {@code null})
 * @param schemaUrl  URL du schéma de conventions sémantiques (peut être {@code null})
 * @param attributes attributs additionnels (jamais {@code null})
 */
public record InstrumentationScope(
        String name, String version, String schemaUrl, Attributes attributes) {

    public InstrumentationScope {
        if (name == null) throw new NullPointerException("name");
        if (attributes == null) attributes = Attributes.empty();
    }

    public static InstrumentationScope of(String name) {
        return new InstrumentationScope(name, null, null, Attributes.empty());
    }
}
