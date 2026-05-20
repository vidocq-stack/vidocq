package io.vidocq.humboldt.sdk.trace.data;

import io.opentelemetry.api.common.Attributes;

/**
 * Événement attaché à un span, immutable.
 *
 * @param epochNanos timestamp en nanosecondes depuis epoch UTC
 * @param name       nom de l'événement (jamais {@code null})
 * @param attributes attributs (jamais {@code null}, peut être vide)
 */
public record EventData(long epochNanos, String name, Attributes attributes) {

    public EventData {
        if (name == null) throw new NullPointerException("name");
        if (attributes == null) attributes = Attributes.empty();
    }
}
