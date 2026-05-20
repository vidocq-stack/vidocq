package io.vidocq.humboldt.sdk.common;

import io.opentelemetry.api.common.Attributes;

import java.util.Objects;

/**
 * Représente la source d'une donnée télémétrique — service, host, container, etc.
 * Immutable.
 *
 * <p>Équivalent fonctionnel de {@code io.opentelemetry.sdk.resources.Resource} sans
 * dépendance au SDK OTel tiers (cf. PLAN.md §3 — Humboldt réimplémente le SDK).</p>
 *
 * <p>Le {@link #schemaUrl()} est optionnel ; lorsqu'il est présent, il référence
 * l'URL d'un schéma de conventions sémantiques OpenTelemetry validant les clés
 * d'attributs portées par cette ressource.</p>
 */
public final class Resource {

    private static final Resource EMPTY = new Resource(Attributes.empty(), null);

    private final Attributes attributes;
    private final String schemaUrl;

    private Resource(Attributes attributes, String schemaUrl) {
        this.attributes = Objects.requireNonNull(attributes, "attributes");
        this.schemaUrl = schemaUrl;
    }

    /**
     * @return la ressource vide (aucun attribut, aucun schema URL).
     */
    public static Resource empty() {
        return EMPTY;
    }

    /**
     * @param attributes attributs de la ressource
     * @return une ressource sans schema URL
     */
    public static Resource create(Attributes attributes) {
        return new Resource(attributes, null);
    }

    /**
     * @param attributes attributs de la ressource
     * @param schemaUrl  URL du schéma de conventions sémantiques (ou {@code null})
     * @return une nouvelle ressource immutable
     */
    public static Resource create(Attributes attributes, String schemaUrl) {
        return new Resource(attributes, schemaUrl);
    }

    public Attributes attributes() {
        return attributes;
    }

    public String schemaUrl() {
        return schemaUrl;
    }

    /**
     * Fusionne cette ressource avec une autre selon les règles OpenTelemetry :
     * les attributs et le {@code schemaUrl} de {@code other} prennent le pas
     * en cas de conflit.
     *
     * @param other ressource à fusionner (peut être {@code null})
     * @return une nouvelle ressource immutable
     */
    public Resource merge(Resource other) {
        if (other == null || other.attributes.isEmpty() && other.schemaUrl == null) {
            return this;
        }
        Attributes merged = this.attributes.toBuilder()
                .putAll(other.attributes)
                .build();
        String url = other.schemaUrl != null ? other.schemaUrl : this.schemaUrl;
        return new Resource(merged, url);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Resource other)) return false;
        return attributes.equals(other.attributes) && Objects.equals(schemaUrl, other.schemaUrl);
    }

    @Override
    public int hashCode() {
        return Objects.hash(attributes, schemaUrl);
    }

    @Override
    public String toString() {
        return "Resource{attributes=" + attributes + ", schemaUrl=" + schemaUrl + "}";
    }
}
