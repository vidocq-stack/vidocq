package io.vidocq.humboldt.sdk.trace.data;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanContext;

/**
 * Lien d'un span vers un autre span (potentiellement dans une autre trace), immutable.
 *
 * @param spanContext contexte du span cible
 * @param attributes  attributs descriptifs du lien
 */
public record LinkData(SpanContext spanContext, Attributes attributes) {

    public LinkData {
        if (spanContext == null) throw new NullPointerException("spanContext");
        if (attributes == null) attributes = Attributes.empty();
    }
}
