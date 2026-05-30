package io.vidocq.humboldt.sdk.trace.data;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanContext;

/**
 * Immutable link from one span to another span (potentially in another trace).
 *
 * @param spanContext target span context
 * @param attributes  descriptive link attributes
 */
public record LinkData(SpanContext spanContext, Attributes attributes) {

    public LinkData {
        if (spanContext == null) throw new NullPointerException("spanContext");
        if (attributes == null) attributes = Attributes.empty();
    }
}
