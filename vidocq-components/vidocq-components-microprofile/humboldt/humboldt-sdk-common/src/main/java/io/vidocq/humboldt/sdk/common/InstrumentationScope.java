package io.vidocq.humboldt.sdk.common;

import io.opentelemetry.api.common.Attributes;

/**
 * Identity of the library that produces a span / metric / log (see OpenTelemetry
 * "Instrumentation Scope").
 *
 * @param name       library name (never {@code null}, may be empty)
 * @param version    library version (may be {@code null})
 * @param schemaUrl  semantic conventions schema URL (may be {@code null})
 * @param attributes additional attributes (never {@code null})
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
