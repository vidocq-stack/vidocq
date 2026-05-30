package io.vidocq.humboldt.sdk.common;

import io.opentelemetry.api.common.Attributes;

import java.util.Objects;

/**
 * Represents the source of telemetry data — service, host, container, etc.
 * Immutable.
 *
 * <p>Functional equivalent of {@code io.opentelemetry.sdk.resources.Resource} without
 * a dependency on the third-party OTel SDK (see PLAN.md §3 — Humboldt reimplements the SDK).</p>
 *
 * <p>{@link #schemaUrl()} is optional; when present, it references
 * the URL of an OpenTelemetry semantic conventions schema validating the
 * attribute keys carried by this resource.</p>
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
     * @return the empty resource (no attributes, no schema URL).
     */
    public static Resource empty() {
        return EMPTY;
    }

    /**
     * @param attributes resource attributes
     * @return a resource without a schema URL
     */
    public static Resource create(Attributes attributes) {
        return new Resource(attributes, null);
    }

    /**
     * @param attributes resource attributes
     * @param schemaUrl  semantic conventions schema URL (or {@code null})
     * @return a new immutable resource
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
     * Merges this resource with another according to the OpenTelemetry rules:
     * the attributes and {@code schemaUrl} of {@code other} take precedence
     * in case of conflict.
     *
     * @param other resource to merge (may be {@code null})
     * @return a new immutable resource
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
